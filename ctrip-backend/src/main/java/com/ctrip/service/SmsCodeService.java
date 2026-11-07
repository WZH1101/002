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
 * 短信验证码：发码、限流、校验。两种场景共用同一张表与同一套配额：
 * <ul>
 *   <li>{@code login}——登录，号码必须已注册且只归属一个账号；</li>
 *   <li>{@code register}——注册，号码必须还没注册（防止拿验证码注册去顶掉别人）。</li>
 * </ul>
 *
 * <p>本项目没有短信网关，所以「发码」是把码写进 {@code sms_code} 表并回显给页面
 * （{@code ctrip.auth.expose-sms-code} 关掉后只写日志）。接真网关时替换掉写表那一步即可，
 * 校验、限流、一次性这些规则不用动。
 *
 * <p>存的始终是 PBKDF2 哈希，不存明文：6 位码只有 100 万种组合，明文入库等于
 * 「5 分钟内谁读一眼数据库谁就能登录」。口令与验证码共用 {@link Crypto}，避免两处参数漂移。
 *
 * <p>限流三档：同一号码 {@code sms-resend-seconds} 内只能发一次、每小时最多
 * {@code sms-max-per-hour} 次；同一 IP 每小时最多 {@code sms-max-per-hour-ip} 次
 * （这档挡住拿一批号码刷配额的行为）。校验侧最多失败 {@code sms-max-attempts} 次，
 * 达到上限这张码立即作废，只能重新获取。
 *
 * <p>代价说明：发码时会说「该手机号未注册 / 已注册」，这给账号枚举留了一条路，
 * 是「验证码只能登录已注册账号、注册码只能给未注册号码」这个选择的必然结果，
 * 缓解手段就是号码与 IP 两维限流。更稳妥的做法是发码一律返回「已发送」，
 * 把账号存在性判断推迟到校验之后。
 */
@Service
public class SmsCodeService {

    private static final Logger log = LoggerFactory.getLogger(SmsCodeService.class);

    public static final String SCENE_LOGIN = "login";
    public static final String SCENE_REGISTER = "register";
    /** 找回密码：号码必须已注册（与登录同一要求），验码通过即可重置口令；多账号时由页面选定重置哪一个 */
    public static final String SCENE_RESET = "reset";

    /** 内存计数器条数上限：超过就清掉不属于当前小时的条目，避免公网长跑把堆吃满（见 throttleIp） */
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

    public SmsCodeService(JdbcTemplate jdbc,
                          @Value("${ctrip.auth.sms-code-ttl-seconds:300}") int ttlSeconds,
                          @Value("${ctrip.auth.sms-resend-seconds:60}") int resendSeconds,
                          @Value("${ctrip.auth.sms-max-per-hour:5}") int maxPerHour,
                          @Value("${ctrip.auth.sms-max-per-hour-ip:20}") int maxPerHourIp,
                          @Value("${ctrip.auth.sms-max-attempts:5}") int maxAttempts,
                          @Value("${ctrip.auth.expose-sms-code:true}") boolean exposeCode) {
        this.jdbc = jdbc;
        this.ttlSeconds = Math.max(30, ttlSeconds);
        this.resendSeconds = Math.max(10, resendSeconds);
        this.maxPerHour = Math.max(1, maxPerHour);
        this.maxPerHourIp = Math.max(1, maxPerHourIp);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.exposeCode = exposeCode;
    }

    /**
     * 登录场景发码。号码必须已注册且只归属一个账号——否则验证码登录会变成「拿别人号码挑账号」。
     *
     * @return expireSeconds / resendSeconds / attemptsLeft；exposeCode 为真时额外带 demoCode
     */
    public Map<String, Object> send(String phone, String clientIp) {
        return sendCode(phone, clientIp, SCENE_LOGIN);
    }

    /**
     * 注册场景发码。号码必须尚未注册：否则等于允许任何人用别人的手机号「覆盖注册」，
     * 而注册码本身并不证明「手机在申请人手里」，只证明「这个号码没人注册过」。
     */
    public Map<String, Object> sendRegisterCode(String phone, String clientIp) {
        return sendCode(phone, clientIp, SCENE_REGISTER);
    }

    /**
     * 找回密码发码。号码必须已注册——重置口令等于接管账号，必须先证明「这个号码在你的手机上」。
     * 一个号码挂着多个账号时照样发码：验证码只证明号码归属，要重置哪一个由验码后的选账号环节决定。
     */
    public Map<String, Object> sendResetCode(String phone, String clientIp) {
        return sendCode(phone, clientIp, SCENE_RESET);
    }

    private Map<String, Object> sendCode(String phone, String clientIp, String scene) {
        if (phone == null || !phone.matches("1[3-9]\\d{9}")) {
            throw new IllegalStateException("请输入11位有效手机号码");
        }
        throttleIp(clientIp);

        List<Map<String, Object>> owners = jdbc.queryForList(
                "SELECT id FROM app_user WHERE phone = ? ORDER BY id", phone);
        // 登录与找回密码：号码必须已注册（一个号码挂多个账号也允许发码，验码后由页面选账号）；注册场景则相反，号码必须未注册
        if (SCENE_LOGIN.equals(scene) || SCENE_RESET.equals(scene)) {
            if (owners.isEmpty()) {
                throw new IllegalStateException("该手机号未注册，请先注册");
            }
        } else {
            if (!owners.isEmpty()) {
                throw new IllegalStateException("该手机号已注册，请直接登录（或换个未注册的号码）");
            }
        }
        throttlePhone(phone);

        // 同一号码同一场景只保留最新一张有效码，旧码立即作废——否则用户能攒着几张码轮流试
        jdbc.update("UPDATE sms_code SET used = 1 WHERE phone = ? AND scene = ? AND used = 0",
                phone, scene);

        String code = String.valueOf(100_000 + Crypto.nextInt(900_000));
        String salt = Crypto.randomSaltHex();
        Instant expireAt = Instant.now().plusSeconds(ttlSeconds);
        jdbc.update("INSERT INTO sms_code(phone, scene, code_hash, expire_time) VALUES(?,?,?,?)",
                phone, scene, Crypto.encode(code, salt), Timestamp.from(expireAt));
        purgeExpired();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scene", scene);
        out.put("expireSeconds", ttlSeconds);
        out.put("resendSeconds", resendSeconds);
        out.put("attemptsLeft", maxAttempts);
        if (exposeCode) {
            // 演示开关：没有短信网关，页面得让用户看得见码，否则这个功能没法自测。
            // 接了真网关或服务对外可达时，把 ctrip.auth.expose-sms-code 设为 false。
            out.put("demoCode", code);
        } else {
            // 关掉回显时明文只进日志。这一行上线前应该删掉——真实项目里码只走短信通道。
            log.info("短信验证码已发送（未接网关，明文码只写这条日志）：phone={} scene={} code={}",
                    phone, scene, code);
        }
        return out;
    }

    /**
     * 校验并消费验证码。返回 false 时调用方给统一提示，不区分「错」与「过期」——
     * 区分它们等于给攻击者一个免费的进度探针。
     */
    public boolean verifyLoginCode(String phone, String code) {
        return verifyCode(phone, code, SCENE_LOGIN);
    }

    /** 注册场景校验：一次性、尝试次数、有效期规则与登录码完全一致 */
    public boolean verifyRegisterCode(String phone, String code) {
        return verifyCode(phone, code, SCENE_REGISTER);
    }

    /** 找回密码场景校验：规则与登录码完全一致，只是 scene 不同，一张码不能既登录又改密 */
    public boolean verifyResetCode(String phone, String code) {
        return verifyCode(phone, code, SCENE_RESET);
    }

    private boolean verifyCode(String phone, String code, String scene) {
        if (phone == null || code == null) return false;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, code_hash, attempts FROM sms_code"
                        + " WHERE phone = ? AND scene = ? AND used = 0 AND expire_time > NOW()"
                        + " ORDER BY id DESC LIMIT 1", phone, scene);
        if (rows.isEmpty()) {
            return false;
        }
        Map<String, Object> row = rows.get(0);
        Object id = row.get("id");
        int attempts = ((Number) row.get("attempts")).intValue();
        if (attempts >= maxAttempts) {
            // 尝试次数早已用满：这张码即使还没到有效期也不该继续可用
            jdbc.update("UPDATE sms_code SET used = 1 WHERE id = ?", id);
            return false;
        }
        if (!Crypto.matchesKdf(code, (String) row.get("code_hash"))) {
            if (attempts + 1 >= maxAttempts) {
                jdbc.update("UPDATE sms_code SET attempts = attempts + 1, used = 1 WHERE id = ? AND used = 0", id);
            } else {
                jdbc.update("UPDATE sms_code SET attempts = attempts + 1 WHERE id = ? AND used = 0", id);
            }
            return false;
        }
        // 一次性：置 used 的条件里带上 used=0，并只认「本次真的改到了这一行」。
        // 先读后写而不判影响行数的话，两个并发请求都读到未使用的行、都返回 true，
        // 同一个码就能登两次（注册同理），5 分钟窗口内等于没有一次性。
        return jdbc.update("UPDATE sms_code SET attempts = attempts + 1, used = 1 WHERE id = ? AND used = 0", id) == 1;
    }

    /**
     * 同一号码的重发间隔与每小时条数。
     *
     * <p>两个计数都跨场景合并：登录码被限就改要注册码，
     * 是同一件事的两个入口，分开算等于把配额翻倍。号码维度的上限因此是「这个号码一小时总共几条码」。
     *
     * <p>已知代价：这是「先查后插」，同一毫秒并发发码可能双双通过重发检查，多出一条码。
     * 真要钉死需要在 (phone, scene) 上加带时间片的唯一键或行锁，演示规模下不值得，
     * 且多发一条并不会绕过一次性与尝试次数上限。
     */
    private void throttlePhone(String phone) {
        Long recent = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sms_code WHERE phone = ? AND create_time > NOW() - INTERVAL ? SECOND",
                Long.class, phone, resendSeconds);
        if (recent != null && recent > 0) {
            throw new IllegalStateException("验证码已发送，请 " + resendSeconds + " 秒后再获取");
        }
        Long hour = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sms_code WHERE phone = ? AND create_time > NOW() - INTERVAL 1 HOUR",
                Long.class, phone);
        if (hour != null && hour >= maxPerHour) {
            throw new IllegalStateException("该手机号获取验证码过于频繁，请 1 小时后再试（每小时最多 "
                    + maxPerHour + " 次）");
        }
    }

    /** 同一 IP 每小时的发码数；计数器在内存，见字段注释的取舍 */
    private void throttleIp(String clientIp) {
        String ip = clientIp == null ? "-" : clientIp;
        long nowHour = System.currentTimeMillis() / 3_600_000;
        // 内存计数器的唯一风险是无界增长：公网跑久了每个访客都留一条记录。
        // 到阈值就顺手清掉所有「不属于当前小时」的条目，比引入 Caffeine 便宜，效果一样。
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
        jdbc.update("DELETE FROM sms_code WHERE expire_time < NOW() - INTERVAL 1 DAY");
    }
}
