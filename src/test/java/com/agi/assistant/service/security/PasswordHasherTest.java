package com.agi.assistant.service.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PasswordHasher} 测试。
 * <p>
 * 重点不在「能算出哈希」，而在几条容易写错的安全性质：
 * 同密码必须产生不同哈希（随机盐）、错误密码必须失败、
 * 畸形记录不能抛异常把登录接口打成 500。
 */
class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    @DisplayName("哈希格式为 pbkdf2$迭代次数$盐$密钥")
    void hashFormatIsStable() {
        String hash = hasher.hash("MyPassw0rd!");

        String[] parts = hash.split("\\$");
        assertThat(parts).hasSize(4);
        assertThat(parts[0]).isEqualTo("pbkdf2");
        assertThat(parts[1]).isEqualTo("120000");
        assertThat(parts[2]).isNotBlank();
        assertThat(parts[3]).isNotBlank();
    }

    @Test
    @DisplayName("正确密码校验通过，错误密码校验失败")
    void matchesOnlyCorrectPassword() {
        String hash = hasher.hash("correct-horse-battery");

        assertThat(hasher.matches("correct-horse-battery", hash)).isTrue();
        assertThat(hasher.matches("wrong-password", hash)).isFalse();
        assertThat(hasher.matches("correct-horse-batter", hash)).isFalse();
        assertThat(hasher.matches("Correct-horse-battery", hash)).isFalse();
    }

    @Test
    @DisplayName("同一密码两次哈希结果不同（随机盐），但都能校验通过")
    void saltMakesHashesUnique() {
        String hash1 = hasher.hash("same-password");
        String hash2 = hasher.hash("same-password");

        assertThat(hash1).isNotEqualTo(hash2);
        assertThat(hasher.matches("same-password", hash1)).isTrue();
        assertThat(hasher.matches("same-password", hash2)).isTrue();
    }

    @Test
    @DisplayName("空密码不允许哈希")
    void emptyPasswordRejected() {
        assertThatThrownBy(() -> hasher.hash(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> hasher.hash(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null / 空白存储值一律校验失败，不抛异常")
    void nullAndBlankStoredValuesFailSafely() {
        assertThat(hasher.matches(null, "pbkdf2$1$a$b")).isFalse();
        assertThat(hasher.matches("pw", null)).isFalse();
        assertThat(hasher.matches("pw", "")).isFalse();
        assertThat(hasher.matches("pw", "   ")).isFalse();
    }

    @Test
    @DisplayName("畸形记录返回 false 而不是抛异常")
    void malformedRecordsFailSafely() {
        // 迭代次数不是数字
        assertThat(hasher.matches("pw", "pbkdf2$abc$AAAA$AAAA")).isFalse();
        // 盐/密钥不是合法 base64
        assertThat(hasher.matches("pw", "pbkdf2$1000$!!!!$!!!!")).isFalse();
        // 段数不对且与明文不等
        assertThat(hasher.matches("pw", "pbkdf2$1000$AAAA")).isFalse();
    }

    @Test
    @DisplayName("历史明文密码可平滑校验（迁移期兼容）")
    void legacyPlaintextStillVerifies() {
        assertThat(hasher.matches("123456", "123456")).isTrue();
        assertThat(hasher.matches("123456", "654321")).isFalse();
    }

    @Test
    @DisplayName("迭代次数取自存储串，因此调参不会让旧密码失效")
    void iterationsAreReadFromStoredValue() {
        // 用很低的迭代次数手写一条记录，验证校验时使用的是「记录里的」迭代次数
        String lowIterationHash = hasher.hash("legacy-pw");
        String rewritten = lowIterationHash.replace("$120000$", "$1000$");

        // 迭代次数被改过 → 派生结果不同 → 校验必须失败（而不是抛异常）
        assertThat(hasher.matches("legacy-pw", rewritten)).isFalse();
        // 原记录仍然有效
        assertThat(hasher.matches("legacy-pw", lowIterationHash)).isTrue();
    }
}
