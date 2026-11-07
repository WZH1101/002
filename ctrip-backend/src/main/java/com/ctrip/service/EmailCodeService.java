package com.ctrip.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 邮箱验证码：发码、限流、校验。规则与 {@link SmsCodeService} 完全同构，两种场景共用一张表：
 * <ul>
 *   <li>{@code register}——注册，邮箱必须还没注册；</li>
 *   <li>{@code login}——登录，邮箱必须已注册且只归属一个账号。</li>
 * </ul>
 *
 * <p>为什么不复用 sms_code 加一个通用 target 列：那张表的列名、限流维度（按号码合算的跨场景配额）
 * 与文案都是手机号语义，混进来会让「一个邮箱一小时几条码」变成按一个含糊字符串计数，
 * 排查时谁也说不清那条记录到底是发给手机还是发给邮箱。
 *
 * <p><b>本项目没有 SMTP 依赖</b>（离线 Maven 仓库里没有 spring-boot-starter-mail / jakarta.mail，
 * 也不为演示下载它们），所以「发信」这一步是把码写进 {@code email_code} 表并回显给页面
 * （{@code ctrip.auth.expose-email-code} 关掉后只写日志）。接真邮箱时把 {@link #deliver}
 * 那一步换成 SMTP/事务型发信即可，校验、限流、一次性这些规则一行都不用动。
 *
 * <p>存的始终是 PBKDF2 哈希，与口令、短信码共用 {@link Crypto} 同一套参数：
 * 6 位码只有 100 万种组合，明文入库等于「10 分钟内谁读一眼数据库谁就能注册任意邮箱账号」。
 *
 * <p>限流三档：同一地址 {@code email-resend-seconds} 内只能发一次、每小时最多
 * {@code email-max-per-hour} 次；同一 IP 每小时最多 {@code email-max-per-hour-ip} 次。
 * 校验侧最多失败 {@code email-max-attempts} 次，达到上限这张码立即作废。
 *
 * <p>已知代价与短信一致：发码时会说「该邮箱已注册 / 未注册」，等于给账号枚举留了一条路，
 * 缓解手段就是地址与 IP 两维限流。
 */
@Service
public class EmailCodeService {

    private static final Logger log = LoggerFactory.getLogger(EmailCodeService.class);

    public static final String SCENE_LOGIN = "login";
    public static final String SCENE_REGISTER = "register";
    /** 找回密码：邮箱必须已注册且唯一归属（与登录同一要求），验码通过即重置口令 */
    public static final String SCENE_RESET = "reset";

    /**
     * 邮箱形态：本地部分允许字母数字与 . _ % + -，域名允许字母数字 . -，顶级域至少两位字母。
     * 刻意不做 RFC 5322 全量校验——那会放行一堆现实中不存在的地址，而真正能证明地址可用的
     * 只有「收到了码」这一步，前端规则只是省一次往返。
     */
    private static final String EMAIL_PATTERN = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$";

    /** email 列是 VARCHAR(128)：超长直接拒，别让 MySQL 报 Data too long 变成一句裸 500 */
    private static final int EMAIL_MAX_LENGTH = 128;

    /** 内存计数器条数上限：超过就清掉不属于当前小时的条目（与短信、扫码同一口径） */
    private static final int COUNTER_CAP = 10_000;

    private final JdbcTemplate jdbc;
    private final int ttlSeconds;
    private final int resendSeconds;
    private final int maxPerHour;
    private final int maxPerHourIp;
    private final int maxAttempts;
    private final boolean exposeCode;

    /** 同一 IP 的每小时发码窗口：{次数, 小时序号}。单机内存，多实例应换 Redis */
    private final Map<String, long[]> ipWindow = new ConcurrentHashMap<>();

    public EmailCodeService(JdbcTemplate jdbc,
                           @Value("${ctrip.auth.email-code-ttl-seconds:600}") int ttlSeconds,
                           @Value("${ctrip.auth.email-resend-seconds:60}") int resendSeconds,
                           @Value("${ctrip.auth.email-max-per-hour:5}") int maxPerHour,
                           @Value("${ctrip.auth.email-max-per-hour-ip:20}") int maxPerHourIp,
                           @Value("${ctrip.auth.email-max-attempts:5}") int maxAttempts,
                           @Value("${ctrip.auth.expose-email-code:true}") boolean exposeCode) {
        this.jdbc = jdbc;
        // 邮箱比短信读得慢（很多人一天看两次邮箱），默认有效期给 10 分钟；下限 60 秒防止配成 0
        this.ttlSeconds = Math.max(60, ttlSeconds);
        this.resendSeconds = Math.max(10, resendSeconds);
        this.maxPerHour = Math.max(1, maxPerHour);
        this.maxPerHourIp = Math.max(1, maxPerHourIp);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.exposeCode = exposeCode;
    }

    /** 规整成入库与比对用的形态：去空白并转小写。MySQL 的 unicode_ci 排序本身不区分大小写，
     *  但把规整放在应用层，唯一索引的判定才和「用户下一次填什么」无关 */
    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    /** 形态校验（不做大小写规整，错误文案里要回显用户填的原样） */
    public static boolean isEmail(String email) {
        String e = normalize(email);
        return e != null && !e.isEmpty() && e.length() <= EMAIL_MAX_LENGTH && e.matches(EMAIL_PATTERN);
    }

    /** 发注册码：邮箱必须尚未注册。规则与校验、限流与登录码同源 */
    public Map<String, Object> sendRegisterCode(String email, String clientIp) {
        return sendCode(email, clientIp, SCENE_REGISTER);
    }

    /** 发登录码：邮箱必须已注册且唯一归属 */
    public Map<String, Object> sendLoginCode(String email, String clientIp) {
        return sendCode(email, clientIp, SCENE_LOGIN);
    }

    /** 发找回密码码：邮箱必须已注册且唯一归属（与登录同一要求） */
    public Map<String, Object> sendResetCode(String email, String clientIp) {
        return sendCode(email, clientIp, SCENE_RESET);
    }

    private Map<String, Object> sendCode(String rawEmail, String clientIp, String scene) {
        String email = normalize(rawEmail);
        if (!isEmail(rawEmail)) {
            throw new IllegalStateException("请输入有效的邮箱地址");
        }
        throttleIp(clientIp);

        List<Map<String, Object>> owners = jdbc.queryForList(
                "SELECT id FROM app_user WHERE email = ? ORDER BY id", email);
        // 登录与找回密码同一归属要求：邮箱必须已注册；注册场景则相反，邮箱必须未注册
        if (SCENE_LOGIN.equals(scene) || SCENE_RESET.equals(scene)) {
            if (owners.isEmpty()) {
                throw new IllegalStateException("该邮箱未注册，请先注册");
            }
            if (owners.size() > 1) {
                // 唯一索引让这不可能发生，留着这段是为了万一有人手工往库里塞了重复邮箱
                throw new IllegalStateException("该邮箱绑定了 " + owners.size() + " 个账号，无法自动定位，请联系客服");
            }
        } else if (!owners.isEmpty()) {
            throw new IllegalStateException("该邮箱已注册，请直接登录（或换个邮箱）");
        }
        throttleEmail(email);

        // 同一地址同一场景只保留最新一张有效码，旧码立即作废——否则用户能攒着几张轮流试
        jdbc.update("UPDATE email_code SET used = 1 WHERE email = ? AND scene = ? AND used = 0", email, scene);

        String code = String.valueOf(100_000 + Crypto.nextInt(900_000));
        String salt = Crypto.randomSaltHex();
        Instant expireAt = Instant.now().plusSeconds(ttlSeconds);
        jdbc.update("INSERT INTO email_code(email, scene, code_hash, expire_time) VALUES(?,?,?,?)",
                email, scene, Crypto.encode(code, salt), Timestamp.from(expireAt));
        purgeExpired();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scene", scene);
        out.put("email", email);
        out.put("expireSeconds", ttlSeconds);
        out.put("resendSeconds", resendSeconds);
        out.put("attemptsLeft", maxAttempts);
        out.put("transport", exposeCode ? "demo-echo" : "log-only");
        if (exposeCode) {
            // 演示开关：没有 SMTP 通道，页面得让用户看得见码，否则这个功能没法自测。
            // 接了真邮箱或服务对外可达时，把 ctrip.auth.expose-email-code 设为 false。
            out.put("demoCode", code);
        } else {
            deliver(email, scene, code);
        }
        return out;
    }

    /**
     * 真正的投递位置。离线仓库里没有邮件依赖，所以这里只写日志——
     * 接 SMTP 时把这一处换成 JavaMailSender 即可，上面「写表 → 校验 → 一次性」的流程不用动。
     */
    private void deliver(String email, String scene, String code) {
        log.info("邮箱验证码（未接 SMTP，明文码只写这条日志）：email={} scene={} code={} 有效期 {} 秒",
                email, scene, code, ttlSeconds);
    }

    /** 注册场景校验：一次性、尝试次数、有效期规则与登录码完全一致 */
    public boolean verifyRegisterCode(String email, String code) {
        return verifyCode(email, code, SCENE_REGISTER);
    }

    public boolean verifyLoginCode(String email, String code) {
        return verifyCode(email, code, SCENE_LOGIN);
    }

    /** 找回密码场景校验：规则与登录码完全一致，只是 scene 不同 */
    public boolean verifyResetCode(String email, String code) {
        return verifyCode(email, code, SCENE_RESET);
    }

    private boolean verifyCode(String rawEmail, String code, String scene) {
        String email = normalize(rawEmail);
        if (email == null || email.isEmpty() || code == null) return false;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, code_hash, attempts FROM email_code"
                        + " WHERE email = ? AND scene = ? AND used = 0 AND expire_time > NOW()"
                        + " ORDER BY id DESC LIMIT 1", email, scene);
        if (rows.isEmpty()) {
            return false;
        }
        Map<String, Object> row = rows.get(0);
        Object id = row.get("id");
        int attempts = ((Number) row.get("attempts")).intValue();
        if (attempts >= maxAttempts) {
            jdbc.update("UPDATE email_code SET used = 1 WHERE id = ?", id);
            return false;
        }
        if (!Crypto.matchesKdf(code, (String) row.get("code_hash"))) {
            if (attempts + 1 >= maxAttempts) {
                jdbc.update("UPDATE email_code SET attempts = attempts + 1, used = 1 WHERE id = ? AND used = 0", id);
            } else {
                jdbc.update("UPDATE email_code SET attempts = attempts + 1 WHERE id = ? AND used = 0", id);
            }
            return false;
        }
        // 一次性：条件里带上 used=0 且只认「本次真的改到了这一行」，与短信码同一个理由——
        // 先读后写不判行数的话，两个并发请求都读到未使用的行、都返回 true，一张码就能注册两次。
        return jdbc.update("UPDATE email_code SET attempts = attempts + 1, used = 1 WHERE id = ? AND used = 0", id) == 1;
    }

    /**
     * 同一地址的重发间隔与每小时条数；两个计数都跨场景合并（登录码被限就改要注册码，
     * 是同一件事的两个入口，分开算等于把配额翻倍）。
     */
    private void throttleEmail(String email) {
        Long recent = jdbc.queryForObject(
                "SELECT COUNT(*) FROM email_code WHERE email = ? AND create_time > NOW() - INTERVAL ? SECOND",
                Long.class, email, resendSeconds);
        if (recent != null && recent > 0) {
            throw new IllegalStateException("验证码已发送，请 " + resendSeconds + " 秒后再获取");
        }
        Long hour = jdbc.queryForObject(
                "SELECT COUNT(*) FROM email_code WHERE email = ? AND create_time > NOW() - INTERVAL 1 HOUR",
                Long.class, email);
        if (hour != null && hour >= maxPerHour) {
            throw new IllegalStateException("该邮箱获取验证码过于频繁，请 1 小时后再试（每小时最多 "
                    + maxPerHour + " 次）");
        }
    }

    /** 同一 IP 每小时的发码数；计数器在内存，见字段注释的取舍 */
    private void throttleIp(String clientIp) {
        String ip = clientIp == null ? "-" : clientIp;
        long nowHour = System.currentTimeMillis() / 3_600_000;
        if (ipWindow.size() > COUNTER_CAP) ipWindow.values().removeIf(v -> v[1] != nowHour);
        long[] win = ipWindow.compute(ip, (k, v) -> {
            if (v == null || v[1] != nowHour) return new long[]{1, nowHour};
            v[0]++;
            return v;
        });
        if (win != null && win[0] > maxPerHourIp) {
            throw new IllegalStateException("验证码请求过于频繁，请稍后再试（同一网络每小时最多 "
                    + maxPerHourIp + " 次）");
        }
    }

    /** 过期一天的验证码已经没有意义，顺手清掉，否则表只增不减 */
    private void purgeExpired() {
        jdbc.update("DELETE FROM email_code WHERE expire_time < NOW() - INTERVAL 1 DAY");
    }
}
