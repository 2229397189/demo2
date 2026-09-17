package com.agi.assistant.service.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 签发与校验。
 * <p>
 * 实现说明：{@code jjwt-api / jjwt-impl / jjwt-jackson} 早已在 pom 里声明，
 * 但全项目没有任何代码使用它们 —— 也就是说「JWT 依赖」是配好了没接线。
 * 本类把它真正用起来，密钥与有效期取自 {@code app.security.jwt-secret} /
 * {@code app.security.jwt-expire-hours}（这两个配置项此前同样无人消费）。
 */
@Slf4j
@Component
public class JwtService {

    /** 生产环境默认密钥长度的下限：HS256 要求 ≥ 256 bit（32 字节） */
    private static final int MIN_SECRET_BYTES = 32;

    private static final String DEFAULT_DEV_SECRET =
            "agi-assistant-dev-secret-please-change-in-production-0123456789";

    private final SecretKey signingKey;

    /** token 有效期（小时） */
    private final long expireHours;

    public JwtService(@Value("${app.security.jwt-secret:" + DEFAULT_DEV_SECRET + "}") String secret,
                      @Value("${app.security.jwt-expire-hours:168}") long expireHours) {
        byte[] keyBytes = (secret == null ? "" : secret).getBytes(StandardCharsets.UTF_8);

        if (keyBytes.length < MIN_SECRET_BYTES) {
            // 密钥太短会让 HS256 直接抛 WeakKeyException。这里明确报错而不是静默"补救" ——
            // 静默替换密钥会导致重启后所有 token 失效，且掩盖了配置错误。
            throw new IllegalStateException(
                    "app.security.jwt-secret 至少需要 " + MIN_SECRET_BYTES + " 字节（当前 "
                            + keyBytes.length + " 字节）。生产环境请通过环境变量 JWT_SECRET 注入足够长度的随机密钥。");
        }

        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.expireHours = expireHours > 0 ? expireHours : 168;

        if (DEFAULT_DEV_SECRET.equals(secret)) {
            log.warn("正在使用内置的开发默认 JWT 密钥，请在生产环境通过 JWT_SECRET 覆盖");
        }
        log.info("JwtService initialized: expireHours={}, keyBytes={}", this.expireHours, keyBytes.length);
    }

    /**
     * 为用户签发 token。
     *
     * @param userId   用户 ID
     * @param username 用户名（放进 subject，便于日志追踪）
     * @return 签名后的 JWT
     */
    public String generateToken(Long userId, String username) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(expireHours * 3600);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();
    }

    /**
     * 解析并校验 token。
     *
     * @param token JWT 字符串
     * @return 解析出的声明；token 无效/过期/被篡改时返回 null
     */
    public Claims parse(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            // 签名不匹配 / 过期 / 格式错误 都归到这里，只记 debug：无效 token 是常态噪音
            log.debug("JWT 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从 token 中取出用户 ID。
     *
     * @param token JWT 字符串
     * @return 用户 ID；token 无效时返回 null
     */
    public Long extractUserId(String token) {
        Claims claims = parse(token);
        if (claims == null || claims.getSubject() == null) {
            return null;
        }
        try {
            return Long.valueOf(claims.getSubject());
        } catch (NumberFormatException e) {
            log.debug("JWT subject 不是合法的用户 ID: {}", claims.getSubject());
            return null;
        }
    }

    /**
     * 从 Authorization 头里剥出 token。
     *
     * @param authorizationHeader 形如 {@code Bearer xxx.yyy.zzz} 的请求头
     * @return 纯 token；格式不符时返回 null
     */
    public String resolveToken(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return null;
        }
        String trimmed = authorizationHeader.trim();
        if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = trimmed.substring(7).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }

    public long getExpireHours() {
        return expireHours;
    }
}
