package com.ctrip.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户注册 / 登录 / 会话管理。
 *
 * <p>口令存储：PBKDF2-HMAC-SHA256，120000 轮迭代 + 每用户随机盐，
 * 编码格式 {@code pbkdf2$迭代数$盐$哈希十六进制}，整体长度 95 字符以内，
 * 可直接落进现有 {@code app_user.pwd_hash VARCHAR(128)}，无需改表。
 * 相比原先的 SHA-256(password+salt)（单次哈希，GPU 可高速爆破），慢哈希把每次校验的成本
 * 抬高约三个数量级。历史上以 SHA-256 存储的账号仍可登录，校验通过后自动升级为 PBKDF2。
 *
 * <p>登录令牌：内存 Map（token -> 会话），2 小时过期，重启后需重新登录；过期会话在登录时顺带清理。
 */
@Service
public class UserService {

    private record Session(long userId, String username, String nickname, String phone, Instant expireAt) {}

    private static final long TOKEN_TTL_SECONDS = 2 * 60 * 60;

    private static final String KDF_PREFIX = "pbkdf2";
    private static final int KDF_ITERATIONS = 120_000;
    private static final int KDF_KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 会话数量超过该阈值时，顺带清理已过期的会话，避免长期运行只增不减 */
    private static final int SESSION_SWEEP_THRESHOLD = 200;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;

    public UserService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> register(Map<String, Object> req) {
        String username = str(req.get("username"));
        String password = str(req.get("password"));
        String nickname = str(req.get("nickname"));
        String phone = str(req.get("phone"));

        if (username == null || !username.matches("[A-Za-z0-9_\\u4e00-\\u9fa5]{3,20}")) {
            throw new IllegalStateException("用户名需 3-20 位（中英文、数字、下划线）");
        }
        if (password == null || password.length() < 6) {
            throw new IllegalStateException("密码长度至少 6 位");
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

    public Map<String, Object> login(Map<String, Object> req) {
        String username = str(req.get("username"));
        String password = str(req.get("password"));
        if (username == null || password == null) {
            throw new IllegalStateException("请输入用户名和密码");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, username, pwd_hash, salt, nickname, phone FROM app_user WHERE username = ?", username);
        if (rows.isEmpty()) {
            // 用户不存在与密码错误返回同样的提示，避免账号枚举
            throw new IllegalStateException("用户名或密码错误");
        }
        Map<String, Object> row = rows.get(0);
        String stored = (String) row.get("pwd_hash");
        String salt = (String) row.get("salt");
        if (!passwordMatches(password, stored, salt)) {
            throw new IllegalStateException("用户名或密码错误");
        }
        // 旧版 SHA-256 记录在此透明升级为 PBKDF2，历史账号无需强制改密
        if (!stored.startsWith(KDF_PREFIX + "$")) {
            String upgraded = encodePassword(password, salt);
            jdbc.update("UPDATE app_user SET pwd_hash = ? WHERE id = ?", upgraded, row.get("id"));
        }

        sweepExpiredSessions();
        String token = randomToken();
        sessions.put(token, new Session(((Number) row.get("id")).longValue(),
                (String) row.get("username"), (String) row.get("nickname"), (String) row.get("phone"),
                Instant.now().plusSeconds(TOKEN_TTL_SECONDS)));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("username", row.get("username"));
        out.put("nickname", row.get("nickname"));
        out.put("phone", row.get("phone"));
        return out;
    }

    /** 供拦截器判断 token 是否仍然有效，不抛异常 */
    public boolean isValidToken(String token) {
        if (token == null || token.isEmpty()) return false;
        Session s = sessions.get(token);
        if (s == null) return false;
        if (s.expireAt().isBefore(Instant.now())) {
            sessions.remove(token);
            return false;
        }
        return true;
    }

    public Map<String, Object> me(String token) {
        Session s = token == null ? null : sessions.get(token);
        if (s == null || s.expireAt().isBefore(Instant.now())) {
            if (s != null) sessions.remove(token);
            throw new IllegalStateException("登录已过期，请重新登录");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username", s.username());
        out.put("nickname", s.nickname());
        out.put("phone", s.phone());
        return out;
    }

    public void logout(String token) {
        if (token != null) sessions.remove(token);
    }

    // ==================== 口令散列 ====================

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

    /** 32 位十六进制令牌，来自 SecureRandom（原先用 UUID.randomUUID，熵源较弱） */
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

    private void sweepExpiredSessions() {
        if (sessions.size() < SESSION_SWEEP_THRESHOLD) return;
        Instant now = Instant.now();
        sessions.entrySet().removeIf(entry -> entry.getValue().expireAt().isBefore(now));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }
}
