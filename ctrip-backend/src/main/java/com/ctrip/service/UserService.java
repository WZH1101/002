package com.ctrip.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户注册 / 登录 / 会话管理。
 * 密码存储：SHA-256(password + salt)，salt 每用户随机；
 * 登录令牌：内存 Map（token -> 会话），2 小时过期，重启后需重新登录。
 */
@Service
public class UserService {

    private record Session(long userId, String username, String nickname, String phone, Instant expireAt) {}

    private static final long TOKEN_TTL_SECONDS = 2 * 60 * 60;

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

        String salt = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        jdbc.update("INSERT INTO app_user(username, pwd_hash, salt, nickname, phone) VALUES(?,?,?,?,?)",
                username, hash(password, salt), salt, nickname, phone);
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
        if (rows.isEmpty() || !hash(password, (String) rows.get(0).get("salt")).equals(rows.get(0).get("pwd_hash"))) {
            throw new IllegalStateException("用户名或密码错误");
        }
        Map<String, Object> row = rows.get(0);
        String token = UUID.randomUUID().toString().replace("-", "");
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

    private static String hash(String password, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((password + salt).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("加密失败");
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }
}
