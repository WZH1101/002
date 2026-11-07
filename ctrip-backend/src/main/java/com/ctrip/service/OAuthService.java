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
 * 第三方账号授权（微信 / QQ / 微博 / 支付宝 / 百度）的场景状态机 + 渠道注册表。
 *
 * <p><b>这一层是模拟的，先把话说清楚。</b>真实实现里 openId 只能由<b>服务端</b>拿授权码去渠道的
 * 接口换（{@code /oauth/{provider}/callback?code=..&state=..} → 用 code 换 access_token →
 * 用 access_token 取 openid），前端从头到尾碰不到 openId；换之前本站什么都不知道。
 * 本项目没有渠道 appid/secret，也没有 HTTPS 回调地址，所以这里用一个由本站自己直出的
 * 「模拟授权页」扮演第三方：页面列出固定的几个演示身份，点「同意」就是把那个身份交给本次场景。
 * 因此这套流程验证的是<b>本站这一侧的全部工程口径</b>——state 的不可猜测性、有效期、一次性领取、
 * 绑定唯一、并发认领、限流——而<b>不</b>验证渠道侧的真实性。接真渠道时把 {@link #approve}
 * 的调用方从模拟页换成回调处理器、openId 从请求参数换成接口返回值，其余状态机一行不用改。
 *
 * <p>一次授权 = 一行 {@code oauth_scene}：发起方创建（PENDING）→ 授权页选定身份（APPROVED）
 * → 发起方轮询领取会话令牌并删掉该行。授权页与发起页是两次互不相干的请求，所以状态必须落库
 * （与 {@link QrLoginService} 同一个理由：内存里的场景一重启就找不到）。
 *
 * <p>令牌在「发起方领取」那一刻才签发（{@link UserService#bindOrCreateByOAuth}，首次授权即注册），
 * 数据库里不存在一个躺着等人来取的会话。领取用带状态与有效期条件的 UPDATE 做原子认领：
 * 两个轮询同时到，只有一个能改到状态并拿到令牌。
 *
 * <p>state 是 32 位十六进制（SecureRandom），它本身就是这次授权的全部凭据：
 * 谁拿到授权页地址谁就能替这个场景选身份，谁拿到轮询地址谁就能领走令牌。
 * 所以有效期默认 2 分钟、单用一次，且上线必须整站 HTTPS。
 */
@Service
public class OAuthService {

    public static final String PENDING = "PENDING";
    /** 身份已在授权页选定，等发起方来领 */
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String EXPIRED = "EXPIRED";
    /** 令牌已被某个轮询请求原子认领，不会再出现在接口返回里 */
    private static final String CLAIMED = "CLAIMED";

    /**
     * 演示身份。{@code id} 是授权页上给访客看的选项编号，{@code openId} 是「渠道声称」的账号标识，
     * 只有这张表里列出来的值能被授权——授权页因此不能拿任意字符串当身份，
     * 免得有人把「模拟第三方」用成「用任意 openId 登录任意账号」的后门。
     */
    record Identity(String id, String openId, String nickname, String avatar) { }

    /**
     * 渠道注册表：编码、显示名、用户名前缀、品牌色、演示身份列表。头像只存取色键（演示站没有真实头像 URL）。
     *
     * <p>这四样只在这里写一份：{@code name} 给授权页与提示语，{@code prefix} 给服务端生成的用户名，
     * {@code color} 给模拟授权页与前端圆标。之前名字与前缀各存一处开关，加一个渠道要改三个文件，
     * 漏一个就是「用户名前缀成了 tp_、页面上写第三方」这种不报错的偏差。
     */
    record Provider(String key, String name, String prefix, String color, List<Identity> identities) { }

    private static final Map<String, Provider> PROVIDERS = new LinkedHashMap<>();

    static {
        PROVIDERS.put("wechat", new Provider("wechat", "微信", "wx_", "#07c160", List.of(
                new Identity("w1", "o_yuetu_wx_1001", "阿晨在旅行", "green"),
                new Identity("w2", "o_yuetu_wx_1002", "Momo", "orange"),
                new Identity("w3", "o_yuetu_wx_1003", "沿途有风", "blue"))));
        PROVIDERS.put("qq", new Provider("qq", "QQ", "qq_", "#12b7f0", List.of(
                new Identity("q1", "o_yuetu_qq_2001", "QQ用户·小德", "blue"),
                new Identity("q2", "o_yuetu_qq_2002", "夏天的风", "cyan"),
                new Identity("q3", "o_yuetu_qq_2003", "旅行清单", "purple"))));
        PROVIDERS.put("weibo", new Provider("weibo", "微博", "wb_", "#e6162d", List.of(
                new Identity("b1", "o_yuetu_wb_3001", "旅行博主张子", "orange"),
                new Identity("b2", "o_yuetu_wb_3002", "山海不回头", "red"),
                new Identity("b3", "o_yuetu_wb_3003", "周末去哪儿", "green"))));
        PROVIDERS.put("alipay", new Provider("alipay", "支付宝", "ap_", "#1677ff", List.of(
                new Identity("a1", "o_yuetu_ap_4001", "阿栗的行李箱", "blue"),
                new Identity("a2", "o_yuetu_ap_4002", "慢慢游", "cyan"),
                new Identity("a3", "o_yuetu_ap_4003", "扫码走天下", "green"))));
        PROVIDERS.put("baidu", new Provider("baidu", "百度", "bd_", "#2932e1", List.of(
                new Identity("d1", "o_yuetu_bd_5001", "搜索来的风", "purple"),
                new Identity("d2", "o_yuetu_bd_5002", "贴吧老旅友", "blue"),
                new Identity("d3", "o_yuetu_bd_5003", "知道分子", "orange"))));
    }

    private final JdbcTemplate jdbc;
    private final UserService userService;
    private final int ttlSeconds;
    private final int maxStartPerMinute;
    /** 授权页地址前缀；留空则按请求的 scheme/host/port 推断（localhost 会换局域网 IP） */
    private final String configuredBase;

    /** 同一 IP 的每分钟创建数：一行 oauth_scene 就是一次数据库写入，不能让人拿它刷表 */
    private final Map<String, long[]> startWindow = new ConcurrentHashMap<>();
    private static final int COUNTER_CAP = 10_000;

    public OAuthService(JdbcTemplate jdbc,
                        UserService userService,
                        @Value("${ctrip.auth.oauth-ttl-seconds:120}") int ttlSeconds,
                        @Value("${ctrip.auth.oauth-max-per-minute:10}") int maxStartPerMinute,
                        @Value("${ctrip.auth.oauth-authorize-base:}") String configuredBase) {
        this.jdbc = jdbc;
        this.userService = userService;
        this.ttlSeconds = Math.max(30, ttlSeconds);
        this.maxStartPerMinute = Math.max(1, maxStartPerMinute);
        this.configuredBase = configuredBase == null ? "" : configuredBase.trim();
    }

    public static boolean isKnownProvider(String provider) {
        return provider != null && PROVIDERS.containsKey(provider);
    }

    public static String providerName(String provider) {
        Provider p = provider == null ? null : PROVIDERS.get(provider);
        return p == null ? "第三方" : p.name();
    }

    /**
     * 用户名前缀。绑定表里看不出渠道时，用户名就能自己说清这个账号从哪来；
     * 认不出的渠道落到 {@code tp_}（third-party）而不是抛异常，因为这里是在建号路径上
     */
    public static String providerPrefix(String provider) {
        Provider p = provider == null ? null : PROVIDERS.get(provider);
        return p == null ? "tp_" : p.prefix();
    }

    /** 渠道品牌色：模拟授权页的配色与前端圆标读同一份，不在三处各写一遍 */
    public static String brandColor(String provider) {
        Provider p = provider == null ? null : PROVIDERS.get(provider);
        return p == null ? "#2577e3" : p.color();
    }

    /** 发起一次授权。 */
    public Map<String, Object> start(String provider, String scheme, String host, int port, String clientIp) {
        Provider meta = provider == null ? null : PROVIDERS.get(provider);
        if (meta == null) {
            // 渠道清单从注册表拼，不写死枚举：否则加一个渠道就有一句过期的提示语替旧清单打广告
            throw new IllegalStateException("不支持的授权渠道，请从 "
                    + String.join("、", PROVIDERS.values().stream().map(Provider::name).toList()) + " 里选一个");
        }
        throttleStart(clientIp);
        String state = Crypto.randomToken();
        Instant expireAt = Instant.now().plusSeconds(ttlSeconds);
        jdbc.update("INSERT INTO oauth_scene(state, provider, status, expire_time) VALUES(?,?,?,?)",
                state, meta.key(), PENDING, Timestamp.from(expireAt));
        purgeExpired();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", meta.key());
        out.put("providerName", meta.name());
        out.put("state", state);
        out.put("status", PENDING);
        out.put("expireSeconds", ttlSeconds);
        out.put("authorizeUrl", authorizeBase(scheme, host, port) + state);
        return out;
    }

    /** 授权页地址前缀，与扫码确认页同一套推断规则（见 {@link LocalUrl}） */
    private String authorizeBase(String scheme, String host, int port) {
        if (!configuredBase.isEmpty()) {
            return configuredBase.endsWith("=") || configuredBase.contains("state")
                    ? configuredBase
                    : configuredBase + (configuredBase.endsWith("/") ? "" : "/") + "authorize?state=";
        }
        return LocalUrl.root(scheme, host, port) + "/oauth/authorize?state=";
    }

    /**
     * 发起方轮询。PENDING / REJECTED / EXPIRED 只是状态；
     * APPROVED 时原子认领、换账号（首次即注册）并签发会话，所以令牌只会被送达一次。
     *
     * <p>授权页<em>绝对不要</em>调这个接口：状态一旦变成 APPROVED 且被授权页轮询到，
     * 令牌就送给了错误的窗口，发起方只能看到一个「已被领取」。
     */
    public Map<String, Object> poll(String state) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> row = find(state);
        if (row == null) {
            out.put("status", "NOT_FOUND");
            out.put("msg", "授权已失效，请重新发起");
            return out;
        }
        String status = (String) row.get("status");
        String provider = (String) row.get("provider");
        out.put("provider", provider);
        out.put("providerName", providerName(provider));
        if (APPROVED.equals(status)) {
            // 认领条件带 expire_time > NOW()：与扫码同一理由——「已确认的授权在有效期内随时能换成
            // 一个新会话」跟「这个场景只活两分钟」的承诺是矛盾的。
            int claimed = jdbc.update("UPDATE oauth_scene SET status = ? WHERE state = ? AND status = ?"
                            + " AND expire_time > NOW()",
                    CLAIMED, state, APPROVED);
            if (claimed != 1) {
                out.put("status", isExpired(row) ? EXPIRED : "NOT_FOUND");
                out.put("msg", isExpired(row) ? "授权已过期，请重新发起" : "本次授权结果已被领取，请重新发起");
                return out;
            }
            try {
                Map<String, Object> login = userService.bindOrCreateByOAuth(provider,
                        (String) row.get("open_id"), (String) row.get("nickname"), (String) row.get("avatar"));
                jdbc.update("DELETE FROM oauth_scene WHERE state = ?", state);
                out.put("status", APPROVED);
                out.put("nickname", row.get("nickname"));
                out.put("avatar", row.get("avatar"));
                out.put("login", login);
                return out;
            } catch (IllegalStateException e) {
                jdbc.update("DELETE FROM oauth_scene WHERE state = ?", state);
                out.put("status", "NOT_FOUND");
                out.put("msg", e.getMessage());
                return out;
            }
        }
        if (CLAIMED.equals(status)) {
            out.put("status", "NOT_FOUND");
            out.put("msg", "本次授权结果已被领取，请重新发起");
            return out;
        }
        if (isExpired(row) && (PENDING.equals(status))) {
            jdbc.update("UPDATE oauth_scene SET status = ? WHERE state = ? AND status = ?",
                    EXPIRED, state, status);
            status = EXPIRED;
        }
        out.put("status", status);
        out.put("remainSeconds", remainSeconds(row));
        return out;
    }

    /**
     * 授权页点「同意」：把本次场景与表里预置的某个演示身份绑上，等发起方来领。
     *
     * <p>身份只能从 {@link #PROVIDERS} 里选，不接受调用方自报的 openId——真实实现里这一步
     * 根本不存在（openId 来自服务端换码），这里收紧是为了不把模拟页变成后门。
     */
    public Map<String, Object> approve(String state, String identityId) {
        Map<String, Object> row = current(state);
        Provider meta = PROVIDERS.get((String) row.get("provider"));
        Identity identity = meta == null ? null : meta.identities().stream()
                .filter(i -> i.id().equals(identityId)).findFirst().orElse(null);
        if (identity == null) {
            throw new IllegalStateException("请选择一个授权身份");
        }
        int n = jdbc.update("UPDATE oauth_scene SET status = ?, open_id = ?, nickname = ?, avatar = ?,"
                        + " approve_time = NOW() WHERE state = ? AND status = ?",
                APPROVED, identity.openId(), identity.nickname(), identity.avatar(), state, PENDING);
        if (n != 1) {
            throw new IllegalStateException("授权已失效或已被处理，请回到发起页重新进入");
        }
        return result(APPROVED, "已授权给「" + identity.nickname() + "」，请回到发起页继续");
    }

    /** 授权页点「拒绝」：发起方立刻看到「已拒绝」，不用干等到超时 */
    public Map<String, Object> reject(String state) {
        Map<String, Object> row = current(state);
        String status = (String) row.get("status");
        jdbc.update("UPDATE oauth_scene SET status = ? WHERE state = ? AND status IN (?,?)",
                REJECTED, state, PENDING, APPROVED);
        return result(REJECTED, PENDING.equals(status)
                ? "已拒绝本次授权，发起页会立刻看到结果" : "授权已取消");
    }

    /**
     * 授权页渲染用的场景信息：只给渠道、状态与身份列表，不给 openId，也不给剩余凭据。
     * 与 {@link #poll} 的关键区别是这里绝不认领状态。
     */
    public Map<String, Object> peek(String state) {
        Map<String, Object> row = find(state);
        if (row == null) return null;
        String provider = (String) row.get("provider");
        Provider meta = PROVIDERS.get(provider);
        String status = (String) row.get("status");
        if (isExpired(row) && PENDING.equals(status)) {
            jdbc.update("UPDATE oauth_scene SET status = ? WHERE state = ? AND status = ?",
                    EXPIRED, state, status);
            status = EXPIRED;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", provider);
        out.put("providerName", providerName(provider));
        out.put("status", status);
        out.put("remainSeconds", remainSeconds(row));
        // 身份列表只给编号与昵称：openId 是本站与服务之间的约定，不该出现在授权页（哪怕它是模拟的）
        List<Map<String, Object>> identities = new java.util.ArrayList<>();
        if (meta != null) {
            for (Identity i : meta.identities()) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("id", i.id());
                one.put("nickname", i.nickname());
                one.put("avatar", i.avatar());
                identities.add(one);
            }
        }
        out.put("identities", identities);
        return out;
    }

    /**
     * state 是这次授权的唯一凭据，只认 32 位十六进制，别的形态一律当作不存在。
     * 剩余秒数交给 MySQL 的 TIMESTAMPDIFF 算（与扫码同一理由：Connector/J 8 回的是
     * LocalDateTime，在 Java 侧按 Timestamp 判断会把刚生成的场景当成已过期）。
     */
    private Map<String, Object> find(String state) {
        if (state == null || !state.matches("[0-9a-f]{32}")) return null;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, provider, open_id, nickname, avatar,"
                        + " TIMESTAMPDIFF(SECOND, NOW(), expire_time) AS remain_seconds"
                        + " FROM oauth_scene WHERE state = ?", state);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static long remainSeconds(Map<String, Object> row) {
        Object remain = row.get("remain_seconds");
        if (!(remain instanceof Number n)) return 0;
        return Math.max(0, n.longValue());
    }

    private static boolean isExpired(Map<String, Object> row) {
        return remainSeconds(row) <= 0;
    }

    /** 取当前场景并顺手把「过期但还没改状态」的行钉成 EXPIRED */
    private Map<String, Object> current(String state) {
        Map<String, Object> row = find(state);
        if (row == null) {
            throw new IllegalStateException("授权不存在或已被领取，请回到发起页重新进入");
        }
        String status = (String) row.get("status");
        if (isExpired(row) && (PENDING.equals(status) || APPROVED.equals(status))) {
            jdbc.update("UPDATE oauth_scene SET status = ? WHERE state = ? AND status = ?",
                    EXPIRED, state, status);
            throw new IllegalStateException("授权已过期，请回到发起页重新进入");
        }
        return row;
    }

    /** 同一 IP 每分钟能发起多少次授权；一次创建就是一行数据库写入，与扫码同一口径 */
    private void throttleStart(String clientIp) {
        String ip = clientIp == null ? "-" : clientIp;
        long nowMin = System.currentTimeMillis() / 60_000;
        if (startWindow.size() > COUNTER_CAP) startWindow.values().removeIf(v -> v[1] != nowMin);
        long[] win = startWindow.compute(ip, (k, v) -> {
            if (v == null || v[1] != nowMin) return new long[]{1, nowMin};
            v[0]++;
            return v;
        });
        if (win != null && win[0] > maxStartPerMinute) {
            throw new IllegalStateException("授权请求过于频繁，请稍后再试（每分钟最多 "
                    + maxStartPerMinute + " 次）");
        }
    }

    private void purgeExpired() {
        jdbc.update("DELETE FROM oauth_scene WHERE expire_time < NOW() - INTERVAL 1 DAY");
    }

    private static Map<String, Object> result(String status, String msg) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        out.put("msg", msg);
        return out;
    }
}
