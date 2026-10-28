package com.ctrip.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
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
 */
@Service
public class UserService {

    private static final long TOKEN_TTL_SECONDS = 2 * 60 * 60;

    private static final String KDF_PREFIX = "pbkdf2";
    private static final int KDF_ITERATIONS = 120_000;
    private static final int KDF_KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

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

    public UserService(JdbcTemplate jdbc,
                       @Value("${ctrip.auth.max-login-failures:5}") int maxLoginFailures,
                       @Value("${ctrip.auth.lock-minutes:15}") long lockMinutes,
                       @Value("${ctrip.auth.max-register-per-hour:5}") int maxRegistersPerHour) {
        this.jdbc = jdbc;
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

    private record Attempt(int failures, Instant lockedUntil) {}

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
        if (nickname == null || nickname.isEmpty()) {
            nickname = username;
        }

        Long exists = jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE username = ?", Long.class, username);
        if (exists != null && exists > 0) {
            throw new IllegalStateException("用户名已被注册");
        }

        String salt = randomSaltHex();
        jdbc.update("INSERT INTO app_user(username, pwd_hash, salt, nickname, phone) VALUES(?,?,?,?,?)",
                username, encodePassword(password, salt), salt, nickname, phone);
        Long id = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, username);

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", id);
        user.put("username", username);
        user.put("nickname", nickname);
        return user;
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
        if (!stored.startsWith(KDF_PREFIX + "$")) {
            jdbc.update("UPDATE app_user SET pwd_hash = ? WHERE id = ?", encodePassword(password, salt), row.get("id"));
        }

        String token = randomToken();
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

        String salt = randomSaltHex();
        jdbc.update("UPDATE app_user SET pwd_hash = ?, salt = ?, auth_ver = auth_ver + 1 WHERE id = ?",
                encodePassword(newPassword, salt), salt, u.id());
        jdbc.update("DELETE FROM user_session WHERE user_id = ?", u.id());
        // 失败计数按用户名维度残留已无意义，一并清掉
        loginAttempts.keySet().removeIf(k -> k.startsWith(u.username() + "@"));
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
        Attempt a = loginAttempts.compute(key, (k, old) -> {
            int failures = (old == null ? 0 : old.failures()) + 1;
            Instant locked = failures >= maxLoginFailures
                    ? Instant.now().plus(Duration.ofMinutes(loginLockMinutes)) : null;
            return new Attempt(failures, locked);
        });
        return a != null && a.lockedUntil() != null;
    }

    private void throttleRegister(String clientIp) {
        String ip = clientIp == null ? "-" : clientIp;
        long nowHour = System.currentTimeMillis() / 3_600_000;
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

    // ==================== 口令散列 ====================

    private static boolean isStrongPassword(String password) {
        return password != null
                && password.length() >= 8
                && password.matches(".*[A-Za-z].*")
                && password.matches(".*\\d.*");
    }

    /** 生成 pbkdf2$迭代数$盐$哈希 形式的存储值 */
    private static String encodePassword(String password, String salt) {
        return KDF_PREFIX + "$" + KDF_ITERATIONS + "$" + salt + "$"
                + toHex(pbkdf2(password, salt, KDF_ITERATIONS));
    }

    /** 兼容校验：新格式走 PBKDF2，旧的单次 SHA-256 记录按原算法比对 */
    private static boolean passwordMatches(String raw, String stored, String salt) {
        if (raw == null || stored == null || salt == null) return false;
        if (stored.startsWith(KDF_PREFIX + "$")) {
            String[] parts = stored.split("\\$");
            if (parts.length != 4) return false;
            int iterations;
            try {
                iterations = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                return false;
            }
            // 盐以存储值里的为准，避免被列值不一致时误判
            return constantTimeEquals(toHex(pbkdf2(raw, parts[2], iterations)), parts[3]);
        }
        return constantTimeEquals(legacySha256Hex(raw, salt), stored);
    }

    private static byte[] pbkdf2(String password, String salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(
                    password.toCharArray(), salt.getBytes(StandardCharsets.UTF_8), iterations, KDF_KEY_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("口令加密失败");
        }
    }

    /** 历史算法，仅用于旧记录校验 */
    private static String legacySha256Hex(String password, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return toHex(md.digest((password + salt).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("口令加密失败");
        }
    }

    private static String randomSaltHex() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return toHex(bytes);
    }

    /** 32 位十六进制令牌，来自 SecureRandom */
    private static String randomToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return toHex(bytes);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }
}
