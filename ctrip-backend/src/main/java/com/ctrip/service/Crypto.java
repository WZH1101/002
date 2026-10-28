package com.ctrip.service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * 口令 / 密钥类散列与随机串的统一实现。
 *
 * <p>抽出来之前只有 {@link UserService} 在用；现在短信验证码（{@link SmsCodeService}）也要
 * 把 6 位码落库，两处必须走同一套 PBKDF2 参数——否则「口令难爆破、验证码好爆破」，
 * 等于给弱的那一头开门。验证码只存 {@code pbkdf2$迭代数$盐$哈希}，明文码不进数据库。
 *
 * <p>本类无状态，全部静态方法；随机数用 {@link SecureRandom}，不要换成 {@code Random}。
 */
final class Crypto {

    /** 与历史上写死在 UserService 里的参数保持一致，改这里等于让所有已存口令失效 */
    static final int KDF_ITERATIONS = 120_000;
    private static final int KDF_KEY_BITS = 256;
    private static final String KDF_PREFIX = "pbkdf2";

    private static final SecureRandom RANDOM = new SecureRandom();

    private Crypto() { }

    /** 生成 pbkdf2$迭代数$盐$哈希十六进制 形式的存储值 */
    static String encode(String secret, String saltHex) {
        return encode(secret, saltHex, KDF_ITERATIONS);
    }

    static String encode(String secret, String saltHex, int iterations) {
        return KDF_PREFIX + "$" + iterations + "$" + saltHex + "$"
                + toHex(pbkdf2(secret, saltHex, iterations));
    }

    /** 存储值是否是 PBKDF2 格式（旧格式走调用方自己的兼容分支） */
    static boolean isKdfStored(String stored) {
        return stored != null && stored.startsWith(KDF_PREFIX + "$");
    }

    /** PBKDF2 校验：迭代数与盐都以存储值里的为准，避免列表值不一致时误判 */
    static boolean matchesKdf(String raw, String stored) {
        if (raw == null || !isKdfStored(stored)) return false;
        String[] parts = stored.split("\\$");
        if (parts.length != 4) return false;
        int iterations;
        try {
            iterations = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        return constantTimeEquals(toHex(pbkdf2(raw, parts[2], iterations)), parts[3]);
    }

    /** 单次 SHA-256，只为历史口令记录保留；新数据一律走 {@link #encode} */
    static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return toHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("摘要计算失败");
        }
    }

    /** 8 字节随机盐的十六进制，16 个字符 */
    static String randomSaltHex() {
        return toHex(randomBytes(8));
    }

    /** 16 字节随机串的十六进制，32 个字符——登录令牌、扫码场景号都用它 */
    static String randomToken() {
        return toHex(randomBytes(16));
    }

    /** [0, bound) 内的均匀随机整数，用于验证码这类小范围取值 */
    static int nextInt(int bound) {
        return RANDOM.nextInt(bound);
    }

    static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    private static byte[] pbkdf2(String secret, String saltHex, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(
                    secret.toCharArray(), saltHex.getBytes(StandardCharsets.UTF_8), iterations, KDF_KEY_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("口令加密失败");
        }
    }

    private static byte[] randomBytes(int len) {
        byte[] bytes = new byte[len];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
