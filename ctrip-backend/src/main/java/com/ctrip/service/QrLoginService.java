package com.ctrip.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 扫码登录的场景状态机。
 *
 * <p>一次扫码 = 一行 {@code login_qrcode}：电脑创建（PENDING）→ 手机打开确认页并登录
 * （SCANNED）→ 手机点「确认登录」（CONFIRMED）→ 电脑轮询领取会话令牌并把该行删掉。
 * 手机页与电脑轮询是两次互不相干的请求，所以状态必须落库，不能像登录失败计数那样放内存——
 * 内存里的场景一重启就找不到，正在扫码的那次登录会莫名失败。
 *
 * <p>令牌在「电脑领取」那一刻才签发（{@link UserService#issueSession}），数据库里不存在
 * 一个躺着等人来取的会话。领取用一条带状态条件的 UPDATE 做原子认领：
 * 两个轮询同时到，只有一个能改到状态并拿到令牌，另一个只会看到「已被领取」。
 *
 * <p>场景号是 32 位十六进制（SecureRandom），它就是这次登录的全部凭据：
 * 谁拿到 URL 谁就能轮询到令牌。所以有效期默认只有 2 分钟、单用一次，
 * 且上线必须整站 HTTPS——二维码在 HTTP 下等同于把会话令牌贴在公告栏上。
 */
@Service
public class QrLoginService {

    public static final String PENDING = "PENDING";
    public static final String SCANNED = "SCANNED";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String REJECTED = "REJECTED";
    public static final String EXPIRED = "EXPIRED";
    /** 令牌已被某个轮询请求原子认领，不会再出现在接口返回里 */
    private static final String CLAIMED = "CLAIMED";

    private final JdbcTemplate jdbc;
    private final UserService userService;
    private final int ttlSeconds;
    private final int maxCreatePerMinute;
    /** 确认页地址前缀；留空则按请求的 scheme/host/port 推断 */
    private final String configuredBase;

    /** 同一 IP 的每分钟创建数：一行 login_qrcode 就是一次数据库写入，不能让人拿它刷表 */
    private final Map<String, long[]> createWindow = new ConcurrentHashMap<>();
    private static final int COUNTER_CAP = 10_000;

    public QrLoginService(JdbcTemplate jdbc,
                          UserService userService,
                          @Value("${ctrip.auth.qrcode-ttl-seconds:120}") int ttlSeconds,
                          @Value("${ctrip.auth.qrcode-max-per-minute:10}") int maxCreatePerMinute,
                          @Value("${ctrip.auth.qrcode-confirm-base:}") String configuredBase) {
        this.jdbc = jdbc;
        this.userService = userService;
        this.ttlSeconds = Math.max(30, ttlSeconds);
        this.maxCreatePerMinute = Math.max(1, maxCreatePerMinute);
        this.configuredBase = configuredBase == null ? "" : configuredBase.trim();
    }

    /**
     * 创建一次扫码登录场景。
     *
     * @param scheme 请求协议；@param host 请求 Host 头里的地址；@param port 服务端口
     * @param clientIp 创建方的地址，仅用于频率限制
     * @return scene / expireSeconds / confirmUrl（手机确认页，二维码内容就是它）
     */
    public Map<String, Object> create(String scheme, String host, int port, String clientIp) {
        throttleCreate(clientIp);
        String scene = Crypto.randomToken();
        Instant expireAt = Instant.now().plusSeconds(ttlSeconds);
        jdbc.update("INSERT INTO login_qrcode(scene, status, expire_time) VALUES(?,?,?)",
                scene, PENDING, Timestamp.from(expireAt));
        purgeExpired();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scene", scene);
        out.put("status", PENDING);
        out.put("expireSeconds", ttlSeconds);
        out.put("confirmUrl", confirmBase(scheme, host, port) + scene);
        return out;
    }

    /**
     * 确认页地址前缀。配置项优先；没配置时按请求推断——但 {@code localhost} 是
     * 「你这台机器」，手机根本解不开，所以交给 {@link LocalUrl} 换成局域网 IPv4。
     */
    private String confirmBase(String scheme, String host, int port) {
        if (!configuredBase.isEmpty()) {
            return configuredBase.endsWith("=") || configuredBase.contains("scene")
                    ? configuredBase
                    : configuredBase + (configuredBase.endsWith("/") ? "" : "/") + "scan?scene=";
        }
        return LocalUrl.root(scheme, host, port) + "/scan?scene=";
    }

    /**
     * 电脑轮询。PENDING / SCANNED / REJECTED / EXPIRED 只是状态；
     * CONFIRMED 时原子认领、签发会话并删除该行，所以令牌只会被送达一次。
     */
    public Map<String, Object> poll(String scene) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> row = find(scene);
        if (row == null) {
            out.put("status", "NOT_FOUND");
            out.put("msg", "二维码已失效，请刷新后重新扫码");
            return out;
        }
        String status = (String) row.get("status");
        if (CONFIRMED.equals(status)) {
            // 认领条件里带上 expire_time > NOW()：手机确认后电脑可能一直不来领（切页签、关窗口），
            // 这一行会在库里留到清理任务把它删掉。不加时间条件，就等于「确认过的二维码
            // 在 24 小时内随时能被换成一个新会话」，与二维码只活两分钟的承诺相反。
            int claimed = jdbc.update("UPDATE login_qrcode SET status = ? WHERE scene = ? AND status = ?"
                            + " AND expire_time > NOW()",
                    CLAIMED, scene, CONFIRMED);
            if (claimed != 1) {
                if (isExpired(row)) {
                    out.put("status", EXPIRED);
                    out.put("msg", "二维码已过期，请在电脑上刷新后重新扫码");
                } else {
                    // 另一个轮询请求先认领了（用户狂点或网络重试）
                    out.put("status", "NOT_FOUND");
                    out.put("msg", "本次扫码结果已被领取，请重新发起登录");
                }
                return out;
            }
            try {
                Map<String, Object> login = userService.issueSession(((Number) row.get("user_id")).longValue());
                jdbc.update("DELETE FROM login_qrcode WHERE scene = ?", scene);
                out.put("status", CONFIRMED);
                out.put("login", login);
                return out;
            } catch (IllegalStateException e) {
                jdbc.update("DELETE FROM login_qrcode WHERE scene = ?", scene);
                out.put("status", "NOT_FOUND");
                out.put("msg", e.getMessage());
                return out;
            }
        }
        if (CLAIMED.equals(status)) {
            // 只在「原子认领成功」到「删除该行」之间的一瞬间可见：令牌已经发给某个轮询了，
            // 第二个到达的请求不该看到内部状态名，统一按「本次已作废」处理。
            out.put("status", "NOT_FOUND");
            out.put("msg", "本次扫码结果已被领取，请重新发起登录");
            return out;
        }
        if (isExpired(row) && (PENDING.equals(status) || SCANNED.equals(status))) {
            jdbc.update("UPDATE login_qrcode SET status = ? WHERE scene = ? AND status = ?",
                    EXPIRED, scene, status);
            status = EXPIRED;
        }
        out.put("status", status);
        out.put("nickname", row.get("nickname"));
        out.put("remainSeconds", remainSeconds(row));
        return out;
    }

    /**
     * 场景号是这次登录的唯一凭据，只认 32 位十六进制，别的形态一律当作不存在。
     *
     * <p>剩余秒数直接让 MySQL 算（{@code TIMESTAMPDIFF(SECOND, NOW(), expire_time)}），
     * 不在 Java 侧比较时间戳：Connector/J 8 对 DATETIME 列返回的是 {@code LocalDateTime}
     * 而不是 {@code java.sql.Timestamp}，一旦按后者做 instanceof 判断，取不到值就会被当成
     * 「已过期」——二维码刚生成就永远扫不上。让数据库算还顺带保证手机端和电脑端用的是同一个钟。
     */
    private Map<String, Object> find(String scene) {
        if (scene == null || !scene.matches("[0-9a-f]{32}")) return null;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT q.status, q.user_id, TIMESTAMPDIFF(SECOND, NOW(), q.expire_time) AS remain_seconds,"
                        + " u.nickname FROM login_qrcode q LEFT JOIN app_user u ON u.id = q.user_id"
                        + " WHERE q.scene = ?", scene);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 剩余秒数；NULL（数据异常）与非正数都归 0，表示已过期 */
    private static long remainSeconds(Map<String, Object> row) {
        Object remain = row.get("remain_seconds");
        if (!(remain instanceof Number n)) return 0;
        return Math.max(0, n.longValue());
    }

    private static boolean isExpired(Map<String, Object> row) {
        return remainSeconds(row) <= 0;
    }

    /**
     * 同一 IP 每分钟能创建多少个场景。没有这一层，任何人写个循环就能把 login_qrcode 撑大
     * （清理任务只删过期一天的行），顺带把数据库连接和 SecureRandom 一起耗掉。
     * 计数器是单机内存，与登录限速、发码限流同一口径。
     */
    private void throttleCreate(String clientIp) {
        String ip = clientIp == null ? "-" : clientIp;
        long nowMin = System.currentTimeMillis() / 60_000;
        if (createWindow.size() > COUNTER_CAP) createWindow.values().removeIf(v -> v[1] != nowMin);
        long[] win = createWindow.compute(ip, (k, v) -> {
            if (v == null || v[1] != nowMin) return new long[]{1, nowMin};
            v[0]++;
            return v;
        });
        if (win != null && win[0] > maxCreatePerMinute) {
            throw new IllegalStateException("二维码生成过于频繁，请稍后再试（每分钟最多 "
                    + maxCreatePerMinute + " 次）");
        }
    }

    private void purgeExpired() {
        jdbc.update("DELETE FROM login_qrcode WHERE expire_time < NOW() - INTERVAL 1 DAY");
    }

    /** 手机打开确认页、且已用该手机的登录态上报「已扫码」 */
    public Map<String, Object> scan(String scene, long userId) {
        Map<String, Object> row = current(scene);
        String status = (String) row.get("status");
        Long owner = row.get("user_id") == null ? null : ((Number) row.get("user_id")).longValue();
        if (PENDING.equals(status)) {
            int n = jdbc.update("UPDATE login_qrcode SET status = ?, user_id = ?, scan_time = NOW()"
                            + " WHERE scene = ? AND status = ?",
                    SCANNED, userId, scene, PENDING);
            if (n == 1) return result(SCANNED, "扫码成功，请在手机上确认登录");
            // 条件更新没命中 = 有别的账号在同一瞬间扫上了。不判行数的话，
            // 后扫的那台手机会看到「扫码成功」，但场景其实已经归别人（确认时才会被 user_id 挡下）。
            throw new IllegalStateException("该二维码已被其他账号扫码，请在原手机上确认或重新发起");
        }
        if (SCANNED.equals(status)) {
            // 已经有人扫过了：同一账号重复进入确认页是正常路径，换账号则拒绝
            if (owner != null && owner != userId) {
                throw new IllegalStateException("该二维码已被其他账号扫码，请在原手机上确认或重新发起");
            }
            return result(SCANNED, "已在扫码状态，请在手机上确认登录");
        }
        throw new IllegalStateException(statusMessage(status));
    }

    /** 手机点「确认登录」：只改状态，令牌要等电脑轮询时才签发 */
    public Map<String, Object> confirm(String scene, long userId) {
        Map<String, Object> row = current(scene);
        if (!SCANNED.equals(row.get("status"))) {
            throw new IllegalStateException(statusMessage((String) row.get("status")));
        }
        int n = jdbc.update("UPDATE login_qrcode SET status = ?, confirm_time = NOW()"
                        + " WHERE scene = ? AND status = ? AND user_id = ?",
                CONFIRMED, scene, SCANNED, userId);
        if (n != 1) {
            throw new IllegalStateException("二维码状态已变更，请在电脑上重新发起登录");
        }
        return result(CONFIRMED, "已确认登录，请回到电脑继续");
    }

    /** 手机点「取消」：电脑那边会看到「已取消」而不是干等到超时 */
    public Map<String, Object> reject(String scene, long userId) {
        Map<String, Object> row = current(scene);
        if (!SCANNED.equals(row.get("status"))) {
            throw new IllegalStateException(statusMessage((String) row.get("status")));
        }
        jdbc.update("UPDATE login_qrcode SET status = ? WHERE scene = ? AND status = ? AND user_id = ?",
                REJECTED, scene, SCANNED, userId);
        return result(REJECTED, "已取消本次登录");
    }

    /** 取当前场景并顺手把「过期但还没改状态」的行钉成 EXPIRED */
    private Map<String, Object> current(String scene) {
        Map<String, Object> row = find(scene);
        if (row == null) {
            throw new IllegalStateException("二维码不存在或已被领取，请在电脑上重新发起登录");
        }
        String status = (String) row.get("status");
        if (isExpired(row) && (PENDING.equals(status) || SCANNED.equals(status))) {
            jdbc.update("UPDATE login_qrcode SET status = ? WHERE scene = ? AND status = ?",
                    EXPIRED, scene, status);
            throw new IllegalStateException("二维码已过期，请在电脑上刷新后重新扫码");
        }
        return row;
    }

    private static String statusMessage(String status) {
        if (EXPIRED.equals(status)) return "二维码已过期，请在电脑上刷新后重新扫码";
        if (CONFIRMED.equals(status)) return "该二维码已确认过登录，无需重复操作";
        if (REJECTED.equals(status)) return "本次登录已取消，请在电脑上重新发起";
        return "二维码状态已变更，请在电脑上重新发起登录";
    }

    private static Map<String, Object> result(String status, String msg) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        out.put("msg", msg);
        return out;
    }
}
