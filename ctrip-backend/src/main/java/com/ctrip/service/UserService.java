package com.ctrip.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户注册 / 登录 / 会话 / 改密。
 *
 * <p>口令存储：PBKDF2-HMAC-SHA256，120000 轮迭代 + 每用户随机盐，
 * 编码格式 {@code pbkdf2$迭代数$盐$哈希十六进制}，整体长度 95 字符以内，
 * 落在 {@code app_user.pwd_hash VARCHAR(128)}。相比原先的单次 SHA-256（GPU 可高速爆破），
 * 慢哈希把每次校验成本抬高约三个数量级。历史 SHA-256 账号仍可登录，校验通过后透明升级。
 *
 * <p>会话：存 {@code user_session} 表而不是内存 Map。内存 Map 有两个问题——重启即全员掉线、
 * 多实例部署时 A 机器签发的令牌 B 机器不认。表里同时记录签发时的 {@code app_user.auth_ver}，
 * 改密码把 auth_ver +1，所有旧令牌立刻失效（此前改密码不会吊销任何令牌）。
 *
 * <p>登录限速：按「用户名 + 客户端 IP」计数，连续失败 5 次锁 15 分钟；注册按 IP 限频。
 * 没有这层，任何人都能拿字典对着 /api/auth/login 无限撞库。
 * 计数器是单机的（内存 Map），多实例部署应换成 Redis；对本项目够用，且不做成跨实例一致是有意取舍。
 *
 * <p>三种登录方式共用同一套会话签发（{@link #sessionFor}）：账号密码（本类）、
 * 手机验证码（{@link SmsCodeService} 验码后由 {@link #loginBySms} 落账号）、
 * 扫码（手机确认页登录后由 {@link QrLoginService} 调 {@link #issueSession}）。
 * 三条路签出来的令牌完全同构，所以订单等后续接口的鉴权口径不需要区分登录方式。
 */
@Service
public class UserService {

    private static final long TOKEN_TTL_SECONDS = 2 * 60 * 60;

    /**
     * 连续失败多少次后锁定 / 锁定时长（分钟）/ 同一 IP 每小时可注册的账号数。
     * 做成配置项而不是写死的常量：默认值按「公网可访问」设定，本机开发和 qa/smoke.sh
     * 需要在同一个小时里反复注册几个测试账号，否则会被自己的防刷机制挡住。
     * 覆盖方式（application.yml 或环境变量，取其一）：
     * <pre>
     *   ctrip:
     *     auth:
     *       max-login-failures: 5
     *       lock-minutes: 15
     *       max-register-per-hour: 20
     * </pre>
     */
    private final int maxLoginFailures;
    private final long loginLockMinutes;
    private final int maxRegistersPerHour;

    private final JdbcTemplate jdbc;
    private final SmsCodeService sms;

    public UserService(JdbcTemplate jdbc,
                       SmsCodeService sms,
                       @Value("${ctrip.auth.max-login-failures:5}") int maxLoginFailures,
                       @Value("${ctrip.auth.lock-minutes:15}") long lockMinutes,
                       @Value("${ctrip.auth.max-register-per-hour:5}") int maxRegistersPerHour) {
        this.jdbc = jdbc;
        this.sms = sms;
        this.maxLoginFailures = Math.max(1, maxLoginFailures);
        this.loginLockMinutes = Math.max(1, lockMinutes);
        this.maxRegistersPerHour = Math.max(1, maxRegistersPerHour);
    }

    /**
     * 口令强度：至少 8 位，且同时包含字母与数字。
     * 只用于注册和改密——历史上 6 位口令的账号不能被登录逻辑拒绝，否则等于把人锁在门外。
     */
    private static final String PWD_RULE = "密码需至少 8 位，且同时包含字母和数字";

    /**
     * 会话校验：令牌未过期、且签发时的 auth_ver 仍等于用户当前的 auth_ver。
     * 改密码后 auth_ver 变了，这条查询就查不到，旧令牌自然失效。
     */
    private static final String SQL_SESSION_USER =
            "SELECT u.id, u.username, u.nickname, u.phone FROM user_session s"
                    + " JOIN app_user u ON u.id = s.user_id"
                    + " WHERE s.token = ? AND s.expire_time > NOW() AND s.auth_ver = u.auth_ver";

    /** 验证码登录：按号码取账号（列必须与 sessionFor 用到的字段一致） */
    private static final String USER_BY_PHONE =
            "SELECT id, username, nickname, phone, auth_ver FROM app_user WHERE phone = ? ORDER BY id";

    /** 扫码登录：确认页已经验过身份，这里按 id 取回账号签发会话 */
    private static final String USER_FOR_SESSION =
            "SELECT id, username, nickname, phone, auth_ver FROM app_user WHERE id = ?";

    /**
     * 一次「用户名@IP」的失败记录。
     *
     * <p>带 lastFailure 有两个用处：一是「连续失败」本来就该有时间含义——一周里零散输错 5 次
     * 不该触发锁定；二是给内存计数器一个合法的淘汰依据，公网长跑不会只增不减。
     */
    private record Attempt(int failures, Instant lockedUntil, Instant lastFailure) {}

    /** 失败计数器的存活时间：超过一小时没再失败过，之前的计数作废（锁定期本身仍按 lock-minutes 走） */
    private static final Duration ATTEMPT_TTL = Duration.ofHours(1);
    /** 内存计数器的条数上限，超过就淘汰过期条目（见 throttleRegister / recordFailure） */
    private static final int COUNTER_CAP = 10_000;

    /** 登录失败计数与锁定期（单机内存，见类注释的取舍说明） */
    private final Map<String, Attempt> loginAttempts = new ConcurrentHashMap<>();
    /** 注册频率窗口：IP -> {次数, 窗口起始秒} */
    private final Map<String, long[]> registerWindow = new ConcurrentHashMap<>();

    // ==================== 注册 / 登录 ====================

    public Map<String, Object> register(Map<String, Object> req, String clientIp) {
        throttleRegister(clientIp);

        String username = str(req.get("username"));
        String password = str(req.get("password"));
        String nickname = str(req.get("nickname"));
        String phone = str(req.get("phone"));

        if (username == null || !username.matches("[A-Za-z0-9_\\u4e00-\\u9fa5]{3,20}")) {
            throw new IllegalStateException("用户名需 3-20 位（中英文、数字、下划线）");
        }
        if (!isStrongPassword(password)) {
            throw new IllegalStateException(PWD_RULE);
        }
        if (phone != null && !phone.isEmpty() && !phone.matches("1[3-9]\\d{9}")) {
            throw new IllegalStateException("请输入11位有效手机号码");
        }
        nickname = clampNickname(nickname, username);

        Long exists = jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE username = ?", Long.class, username);
        if (exists != null && exists > 0) {
            throw new IllegalStateException("用户名已被注册");
        }

        insertUser(username, password, Crypto.randomSaltHex(), nickname, phone);
        Long id = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, username);

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", id);
        user.put("username", username);
        user.put("nickname", nickname);
        return user;
    }

    /**
     * 手机验证码注册：手机号 + 注册码 + （可选）口令。
     *
     * <p>手机号是唯一凭据，注册码只保证「这个号码还没注册过」（见
     * {@link SmsCodeService#sendRegisterCode} 的取舍），所以这里要求号码唯一；
     * 用户名按 {@code m + 手机号} 自动生成，之后可以用验证码登录，也可用手机号当用户名试密码登录。
     *
     * <p>口令选填：填了必须符合强度规则；留空则生成一个用户不知道的随机口令——
     * 账号只能靠验证码登录，这正是「手机号注册」的预期形态。留空生成随机口令比存「无口令」
     * 标记安全：pwd_hash 永远是有效哈希，登录路径不存在「这条记录没有口令」的特殊分支。
     *
     * @return 与登录同构的会话信息（token/id/username/nickname/phone），注册成功即登录
     */
    public Map<String, Object> registerBySms(Map<String, Object> req, String clientIp) {
        String phone = str(req.get("phone"));
        String code = str(req.get("code"));
        String nickname = str(req.get("nickname"));
        String password = str(req.get("password"));

        if (phone == null || !phone.matches("1[3-9]\\d{9}")) {
            throw new IllegalStateException("请输入11位有效手机号码");
        }
        if (code == null || !code.matches("\\d{6}")) {
            throw new IllegalStateException("请输入6位短信验证码");
        }
        if (password != null && !password.isEmpty() && !isStrongPassword(password)) {
            throw new IllegalStateException(PWD_RULE);
        }
        // 限流放在验码之前：反过来的话，被限流的那一次已经把唯一的一张码消费掉了，
        // 用户等一小时额度恢复之外还要重新发一条，纯亏。
        throttleRegister(clientIp);
        if (!sms.verifyRegisterCode(phone, code)) {
            throw new IllegalStateException("验证码不正确或已失效");
        }

        Long exists = jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE phone = ?", Long.class, phone);
        if (exists != null && exists > 0) {
            // 发码时说「未注册」，此刻又已存在：对方先一步注册了同一个号码，或注册码与号码对不上
            throw new IllegalStateException("该手机号刚刚已被注册，请直接登录");
        }
        // 用户名自动生成：手机号是唯一凭据，m+手机号 也是唯一的；正则允许字母数字下划线
        String username = "m" + phone;
        Long nameTaken = jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE username = ?", Long.class, username);
        if (nameTaken != null && nameTaken > 0) {
            // 理论上只有手工建过一个叫 m13xxxxxxxxx 的账号才会走到这里
            username = username + "_" + Crypto.randomToken().substring(0, 4);
        }
        nickname = clampNickname(nickname, "手机用户" + phone.substring(7));
        if (password == null || password.isEmpty()) {
            // 随机口令：用户不知道它，只能验证码登录；保证同时含字母和数字（前缀 Yt + 十六进制）
            password = "Yt" + Crypto.randomToken().substring(0, 16);
        }

        insertUser(username, password, Crypto.randomSaltHex(), nickname, phone);
        return sessionFor(jdbc.queryForMap(
                "SELECT id, username, nickname, phone, auth_ver FROM app_user WHERE username = ?", username));
    }

    public Map<String, Object> login(Map<String, Object> req, String clientIp) {
        String username = str(req.get("username"));
        String password = str(req.get("password"));
        if (username == null || password == null) {
            throw new IllegalStateException("请输入用户名和密码");
        }

        String key = username + '@' + (clientIp == null ? "-" : clientIp);
        checkNotLocked(key);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, username, pwd_hash, salt, nickname, phone, auth_ver FROM app_user WHERE username = ?", username);
        if (rows.isEmpty() || !passwordMatches(password, (String) rows.get(0).get("pwd_hash"), (String) rows.get(0).get("salt"))) {
            // 用户不存在与密码错误返回同样的提示，避免账号枚举；但两者都要计入失败次数
            if (recordFailure(key)) {
                // 恰好这一次越过阈值：当场说明已锁定，而不是让人再试一次才发现（冒烟脚本第 12 节钉住这个行为）
                throw new IllegalStateException("密码错误次数过多，账号已临时锁定，请 " + loginLockMinutes + " 分钟后再试");
            }
            throw new IllegalStateException("用户名或密码错误");
        }
        loginAttempts.remove(key);

        Map<String, Object> row = rows.get(0);
        String stored = (String) row.get("pwd_hash");
        String salt = (String) row.get("salt");
        // 旧版 SHA-256 记录在此透明升级为 PBKDF2，历史账号无需强制改密
        if (!Crypto.isKdfStored(stored)) {
            jdbc.update("UPDATE app_user SET pwd_hash = ? WHERE id = ?", encodePassword(password, salt), row.get("id"));
        }
        return sessionFor(row);
    }

    /**
     * 手机验证码登录。验证码本身由 {@link SmsCodeService} 校验（有效期、重发间隔、
     * 每小时上限、尝试次数、一次性），这里只负责「号码 → 账号 → 签发会话」。
     *
     * <p>不再叠一层登录失败锁定：6 位码的尝试次数已经被 sms-max-attempts 钉死并在达到上限时
     * 作废，重发另有每小时配额；同一件事做两套计数器反而会出现「哪个先到上限」说不清的情况。
     */
    public Map<String, Object> loginBySms(String phone, String code) {
        if (phone == null || !phone.matches("1[3-9]\\d{9}")) {
            throw new IllegalStateException("请输入11位有效手机号码");
        }
        if (code == null || !code.matches("\\d{6}")) {
            throw new IllegalStateException("请输入6位短信验证码");
        }
        if (!sms.verifyLoginCode(phone, code)) {
            throw new IllegalStateException("验证码不正确或已失效");
        }
        // 一个号码可能挂着多个账号（演示数据里 13800138000 就有三个）。
        // 验证码登录不能变成「用别人的号码挑一个账号登进去」，所以只接受唯一归属。
        List<Map<String, Object>> rows = jdbc.queryForList(USER_BY_PHONE, phone);
        if (rows.isEmpty()) {
            throw new IllegalStateException("该手机号未注册，请先注册或用账号密码登录");
        }
        if (rows.size() > 1) {
            throw new IllegalStateException("该手机号绑定了 " + rows.size()
                    + " 个账号，无法用验证码登录，请改用账号密码登录");
        }
        return sessionFor(rows.get(0));
    }

    /**
     * 扫码确认后为指定账号签发会话。手机点「确认登录」时已经把那个账号的令牌验过了，
     * 电脑这边领取的是新签发的一条会话，不是把手机那条令牌搬过去。
     */
    public Map<String, Object> issueSession(long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(USER_FOR_SESSION, userId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("账号不存在或已被删除");
        }
        return sessionFor(rows.get(0));
    }

    /** 写一条 user_session 并组装登录返回体；token 与表里那条记录一一对应 */
    private Map<String, Object> sessionFor(Map<String, Object> row) {
        String token = Crypto.randomToken();
        jdbc.update("INSERT INTO user_session(token, user_id, auth_ver, expire_time) VALUES(?,?,?,?)",
                token, row.get("id"), row.get("auth_ver"),
                Timestamp.from(Instant.now().plusSeconds(TOKEN_TTL_SECONDS)));
        purgeExpiredSessions();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("id", row.get("id"));
        out.put("username", row.get("username"));
        out.put("nickname", row.get("nickname"));
        out.put("phone", row.get("phone"));
        return out;
    }

    // ==================== 会话 ====================

    /** 供拦截器判断 token 是否仍然有效，不抛异常 */
    public boolean isValidToken(String token) {
        return findAuthUser(token) != null;
    }

    /**
     * 会话身份。不抛异常，令牌无效时返回 null——便于控制器在拦截器之后安全地二次读取，
     * 万一令牌恰好在两次读取之间失效，也只是这一次请求被拒，不会变成 500。
     */
    public record AuthUser(long id, String username, String nickname, String phone) {}

    public AuthUser findAuthUser(String token) {
        if (token == null || token.isEmpty()) return null;
        List<Map<String, Object>> rows = jdbc.queryForList(SQL_SESSION_USER, token);
        if (rows.isEmpty()) return null;
        Map<String, Object> r = rows.get(0);
        return new AuthUser(((Number) r.get("id")).longValue(),
                (String) r.get("username"), (String) r.get("nickname"), (String) r.get("phone"));
    }

    public Map<String, Object> me(String token) {
        AuthUser u = findAuthUser(token);
        if (u == null) {
            throw new IllegalStateException("登录已过期，请重新登录");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", u.id());
        out.put("username", u.username());
        out.put("nickname", u.nickname());
        out.put("phone", u.phone());
        return out;
    }

    /** 退出登录：只删当前这一条会话，其他设备上的令牌不受影响 */
    public void logout(String token) {
        if (token != null && !token.isEmpty()) {
            jdbc.update("DELETE FROM user_session WHERE token = ?", token);
        }
    }

    // ==================== 改密 ====================

    /**
     * 校验旧口令后改密。改完把 auth_ver +1 并清空该用户全部会话：
     * 此前「改密码不会吊销旧令牌」——即使口令泄露被对方改掉，受害者手上的会话也照样能用。
     * 因此调用方在成功后应要求重新登录。
     */
    public void changePassword(String token, String oldPassword, String newPassword) {
        AuthUser u = findAuthUser(token);
        if (u == null) {
            throw new IllegalStateException("登录已过期，请重新登录");
        }
        if (oldPassword == null || newPassword == null) {
            throw new IllegalStateException("请输入原密码和新密码");
        }
        if (!isStrongPassword(newPassword)) {
            throw new IllegalStateException(PWD_RULE);
        }
        if (newPassword.equals(oldPassword)) {
            throw new IllegalStateException("新密码不能与原密码相同");
        }

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT pwd_hash, salt FROM app_user WHERE id = ?", u.id());
        if (!passwordMatches(oldPassword, (String) row.get("pwd_hash"), (String) row.get("salt"))) {
            throw new IllegalStateException("原密码不正确");
        }

        String salt = Crypto.randomSaltHex();
        jdbc.update("UPDATE app_user SET pwd_hash = ?, salt = ?, auth_ver = auth_ver + 1 WHERE id = ?",
                encodePassword(newPassword, salt), salt, u.id());
        jdbc.update("DELETE FROM user_session WHERE user_id = ?", u.id());
        // 失败计数按用户名维度残留已无意义，一并清掉
        loginAttempts.keySet().removeIf(k -> k.startsWith(u.username() + "@"));
    }

    // ==================== 建号 ====================

    /** 昵称列是 VARCHAR(50)：超长截断，而不是让 MySQL 报「Data too long」变成一句裸 500 */
    private static String clampNickname(String nickname, String fallback) {
        String n = (nickname == null || nickname.isEmpty()) ? fallback : nickname;
        return n.length() > 50 ? n.substring(0, 50) : n;
    }

    /**
     * 插入账号。上面的 COUNT 预检查只是为了给一句更清楚的提示，真正的唯一性由
     * {@code app_user.username} 的 UNIQUE 索引兜底：两个请求同时注册同一个用户名（手机号注册
     * 走 {@code m+手机号}，同理）时后到的那个会撞键——不转成业务异常就是一句 500。
     */
    private void insertUser(String username, String password, String salt, String nickname, String phone) {
        try {
            jdbc.update("INSERT INTO app_user(username, pwd_hash, salt, nickname, phone) VALUES(?,?,?,?,?)",
                    username, encodePassword(password, salt), salt, nickname, phone);
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("该账号刚刚已被注册，请直接登录或换一个用户名");
        }
    }

    // ==================== 限速 ====================

    private void checkNotLocked(String key) {
        Attempt a = loginAttempts.get(key);
        if (a == null || a.lockedUntil() == null) return;
        Instant now = Instant.now();
        if (a.lockedUntil().isAfter(now)) {
            long minutes = Math.max(1, Duration.between(now, a.lockedUntil()).toMinutes() + 1);
            throw new IllegalStateException("失败次数过多，账号已临时锁定，请 " + minutes + " 分钟后再试");
        }
        loginAttempts.remove(key);
    }

    /**
     * 记一次登录失败。
     *
     * @return 本次失败是否刚好把账号推进锁定期——调用方据此在同一个响应里就说「已临时锁定」，
     *         省掉「第 N 次只说密码错误、第 N+1 次才说锁定」这种让人以为还能再试的误导。
     */
    private boolean recordFailure(String key) {
        Instant now = Instant.now();
        if (loginAttempts.size() > COUNTER_CAP) {
            loginAttempts.values().removeIf(a -> a.lastFailure().plus(ATTEMPT_TTL).isBefore(now));
        }
        Attempt a = loginAttempts.compute(key, (k, old) -> {
            // 上一次失败已经是一小时前：重新开始数，否则「连续失败 5 次」会变成「累计失败 5 次」
            int previous = old == null || old.lastFailure().plus(ATTEMPT_TTL).isBefore(now) ? 0 : old.failures();
            int failures = previous + 1;
            Instant locked = failures >= maxLoginFailures
                    ? now.plus(Duration.ofMinutes(loginLockMinutes)) : null;
            return new Attempt(failures, locked, now);
        });
        return a != null && a.lockedUntil() != null;
    }

    private void throttleRegister(String clientIp) {
        String ip = clientIp == null ? "-" : clientIp;
        long nowHour = System.currentTimeMillis() / 3_600_000;
        // 内存计数器只增不减迟早吃满堆：到阈值就把「不属于当前小时」的条目清掉
        if (registerWindow.size() > COUNTER_CAP) registerWindow.values().removeIf(v -> v[1] != nowHour);
        long[] win = registerWindow.compute(ip, (k, v) -> {
            if (v == null || v[1] != nowHour) return new long[]{1, nowHour};
            v[0]++;
            return v;
        });
        if (win != null && win[0] > maxRegistersPerHour) {
            throw new IllegalStateException("注册过于频繁，请稍后再试（同一网络每小时最多 " + maxRegistersPerHour + " 个账号）");
        }
    }

    /** 过期会话顺手清掉；保留一天内的记录便于排查「为什么刚才还能用」 */
    private void purgeExpiredSessions() {
        jdbc.update("DELETE FROM user_session WHERE expire_time < DATE_SUB(NOW(), INTERVAL 1 DAY)");
    }

    // ==================== 口令散列（统一走 Crypto） ====================

    private static boolean isStrongPassword(String password) {
        return password != null
                && password.length() >= 8
                && password.matches(".*[A-Za-z].*")
                && password.matches(".*\\d.*");
    }

    /** 生成 pbkdf2$迭代数$盐$哈希 形式的存储值 */
    private static String encodePassword(String password, String salt) {
        return Crypto.encode(password, salt);
    }

    /** 兼容校验：新格式走 PBKDF2，旧的单次 SHA-256 记录按原算法比对 */
    private static boolean passwordMatches(String raw, String stored, String salt) {
        if (raw == null || stored == null || salt == null) return false;
        if (Crypto.isKdfStored(stored)) return Crypto.matchesKdf(raw, stored);
        return Crypto.constantTimeEquals(Crypto.sha256Hex(raw + salt), stored);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }
}
