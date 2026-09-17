package com.agi.assistant.service.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 密码哈希。
 * <p>
 * 选型说明：项目里没有引入 spring-security-crypto（也就没有 BCrypt），
 * 但 JDK 自带 {@code PBKDF2WithHmacSHA256}，属于「密码哈希正确做法」里的可选项，
 * 所以不为了一个哈希函数去引整条 Spring Security 依赖。
 * <p>
 * 存储格式：{@code pbkdf2$<迭代次数>$<base64 盐>$<base64 派生密钥>}
 * <ul>
 *   <li>每个用户独立随机盐 —— 防止相同密码产生相同哈希（彩虹表 / 批量撞库）；</li>
 *   <li>迭代次数写进存储串，未来调参不会让老密码失效；</li>
 *   <li>校验用常量时间比较 —— 避免通过响应时间差逐字节猜测哈希。</li>
 * </ul>
 */
@Slf4j
@Component
public class PasswordHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "pbkdf2";
    private static final int ITERATIONS = 120_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 生成密码哈希。
     *
     * @param rawPassword 明文密码
     * @return 可直接入库的哈希串
     */
    public String hash(String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            throw new IllegalArgumentException("密码不能为空");
        }

        byte[] salt = new byte[SALT_BYTES];
        secureRandom.nextBytes(salt);

        byte[] derived = derive(rawPassword.toCharArray(), salt, ITERATIONS);

        Base64.Encoder encoder = Base64.getEncoder();
        return PREFIX + "$" + ITERATIONS + "$"
                + encoder.encodeToString(salt) + "$"
                + encoder.encodeToString(derived);
    }

    /**
     * 校验密码。
     *
     * @param rawPassword    明文密码
     * @param storedPassword 库中存储的哈希串
     * @return true 表示匹配
     */
    public boolean matches(String rawPassword, String storedPassword) {
        if (rawPassword == null || storedPassword == null || storedPassword.isBlank()) {
            return false;
        }

        String[] parts = storedPassword.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            // 兼容历史明文密码：仅用于平滑迁移，且必须打日志提醒。
            // 不做「明文直接相等」的长期支持 —— 那样等于没有哈希。
            log.warn("检测到非 PBKDF2 格式的密码记录，请尽快迁移（重置该用户密码即可）");
            return constantTimeEquals(rawPassword, storedPassword);
        }

        int iterations;
        try {
            iterations = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            log.warn("密码记录中的迭代次数非法: {}", parts[1]);
            return false;
        }

        byte[] salt;
        byte[] expected;
        try {
            Base64.Decoder decoder = Base64.getDecoder();
            salt = decoder.decode(parts[2]);
            expected = decoder.decode(parts[3]);
        } catch (IllegalArgumentException e) {
            log.warn("密码记录中的盐或密钥不是合法 base64");
            return false;
        }

        byte[] actual = derive(rawPassword.toCharArray(), salt, iterations);
        return MessageDigest.isEqual(expected, actual);
    }

    private byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            // 算法不可用属于环境级故障，直接抛出让启动/调用暴露问题，不静默降级
            throw new IllegalStateException("密码派生失败（" + ALGORITHM + " 不可用）", e);
        } finally {
            spec.clearPassword();
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
