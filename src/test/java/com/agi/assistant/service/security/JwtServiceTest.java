package com.agi.assistant.service.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JwtService} 测试。
 * <p>
 * 身份校验是整个鉴权链的唯一信任来源，因此这里重点验证「不该通过的一律不通过」：
 * 伪造签名、过期、垃圾串、格式错误都必须解析为 null，而不是抛异常或放行。
 */
class JwtServiceTest {

    /** 41 字节，满足 HS256 对 ≥32 字节密钥的要求 */
    private static final String SECRET = "agi-assistant-test-secret-0123456789abcdef";

    private final JwtService jwtService = new JwtService(SECRET, 168);

    // ──────────────────────────────────────────────────────────────
    //  签发 / 解析
    // ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("签发的 token 可解析出 userId 与 username")
    void generateAndParseRoundTrip() {
        String token = jwtService.generateToken(42L, "alice");

        assertThat(token).isNotBlank();
        assertThat(token.split("\\.")).as("JWT 应为三段式").hasSize(3);

        Claims claims = jwtService.parse(token);
        assertThat(claims).isNotNull();
        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.get("username", String.class)).isEqualTo("alice");
        assertThat(claims.getExpiration()).isAfter(new Date());

        assertThat(jwtService.extractUserId(token)).isEqualTo(42L);
    }

    @Test
    @DisplayName("非法 token 一律返回 null，不抛异常")
    void invalidTokensReturnNull() {
        assertThat(jwtService.parse(null)).isNull();
        assertThat(jwtService.parse("")).isNull();
        assertThat(jwtService.parse("   ")).isNull();
        assertThat(jwtService.parse("not-a-jwt")).isNull();
        assertThat(jwtService.parse("aaa.bbb.ccc")).isNull();

        assertThat(jwtService.extractUserId("not-a-jwt")).isNull();
        assertThat(jwtService.extractUserId(null)).isNull();
    }

    @Test
    @DisplayName("被篡改的 token 签名不匹配 → 拒收")
    void tamperedTokenRejected() {
        String token = jwtService.generateToken(1L, "bob");

        // 改掉 payload（第二段），签名随即失效
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "XY." + parts[2];

        assertThat(jwtService.parse(tampered)).isNull();
        assertThat(jwtService.extractUserId(tampered)).isNull();
    }

    @Test
    @DisplayName("用别的密钥签发的 token 也拒收（换密钥即失效）")
    void tokenSignedWithAnotherKeyRejected() {
        SecretKey otherKey = Keys.hmacShaKeyFor(
                "another-different-secret-0123456789abcdef".getBytes(StandardCharsets.UTF_8));

        String foreignToken = Jwts.builder()
                .subject("999")
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(otherKey)
                .compact();

        assertThat(jwtService.parse(foreignToken)).isNull();
        assertThat(jwtService.extractUserId(foreignToken)).isNull();
    }

    @Test
    @DisplayName("过期 token 拒收")
    void expiredTokenRejected() {
        SecretKey sameKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

        String expired = Jwts.builder()
                .subject("7")
                .issuedAt(Date.from(Instant.now().minusSeconds(7200)))
                .expiration(Date.from(Instant.now().minusSeconds(3600)))
                .signWith(sameKey)
                .compact();

        assertThat(jwtService.parse(expired)).isNull();
        assertThat(jwtService.extractUserId(expired)).isNull();
    }

    @Test
    @DisplayName("subject 不是数字时 extractUserId 返回 null")
    void nonNumericSubjectReturnsNull() {
        SecretKey sameKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

        String weird = Jwts.builder()
                .subject("not-a-number")
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(sameKey)
                .compact();

        assertThat(jwtService.parse(weird)).as("签名有效，应能解析").isNotNull();
        assertThat(jwtService.extractUserId(weird)).isNull();
    }

    // ──────────────────────────────────────────────────────────────
    //  配置与请求头解析
    // ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("密钥短于 32 字节时启动即失败（不静默降级）")
    void shortSecretFailsFast() {
        assertThatThrownBy(() -> new JwtService("1234567890123456789012345678901", 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");

        assertThatThrownBy(() -> new JwtService("", 1))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtService(null, 1))
                .isInstanceOf(IllegalStateException.class);

        // 刚好 32 字节 / 41 字节都应通过
        assertThat(new JwtService("12345678901234567890123456789012", 1)).isNotNull();
        assertThat(new JwtService(SECRET, 1)).isNotNull();
    }

    @Test
    @DisplayName("非正的有效期回落到默认 168 小时")
    void nonPositiveExpireHoursFallsBack() {
        assertThat(new JwtService(SECRET, 0).getExpireHours()).isEqualTo(168);
        assertThat(new JwtService(SECRET, -5).getExpireHours()).isEqualTo(168);
        assertThat(new JwtService(SECRET, 24).getExpireHours()).isEqualTo(24);
    }

    @Test
    @DisplayName("Authorization 头解析：大小写不敏感、严格 Bearer 前缀")
    void resolveTokenParsesAuthorizationHeader() {
        assertThat(jwtService.resolveToken("Bearer abc.def.ghi")).isEqualTo("abc.def.ghi");
        assertThat(jwtService.resolveToken("bearer abc.def.ghi")).isEqualTo("abc.def.ghi");
        assertThat(jwtService.resolveToken("BEARER abc.def.ghi")).isEqualTo("abc.def.ghi");
        assertThat(jwtService.resolveToken("  Bearer   abc.def.ghi  ")).isEqualTo("abc.def.ghi");

        // 缺少 Bearer 前缀 / 空 token 一律 null —— 不给客户端自定义身份留口子
        assertThat(jwtService.resolveToken("abc.def.ghi")).isNull();
        assertThat(jwtService.resolveToken("Basic dXNlcjpwYXNz")).isNull();
        assertThat(jwtService.resolveToken("Bearer")).isNull();
        assertThat(jwtService.resolveToken("Bearer    ")).isNull();
        assertThat(jwtService.resolveToken(null)).isNull();
        assertThat(jwtService.resolveToken("")).isNull();
    }
}
