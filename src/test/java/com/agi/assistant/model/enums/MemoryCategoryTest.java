package com.agi.assistant.model.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MemoryCategory} 归一口径单测：无论输入大小写 / 是否合法，都收敛到 5 个规范 token。
 *
 * @author Alex
 */
class MemoryCategoryTest {

    @Test
    @DisplayName("fact / FACT / Fact 均归一为规范 token FACT")
    void normalizeIsCaseInsensitive() {
        assertThat(MemoryCategory.normalize("fact")).isEqualTo("FACT");
        assertThat(MemoryCategory.normalize("FACT")).isEqualTo("FACT");
        assertThat(MemoryCategory.normalize("Fact")).isEqualTo("FACT");
        assertThat(MemoryCategory.normalize("  preference ")).isEqualTo("PREFERENCE");
    }

    @Test
    @DisplayName("null / 空 / 未知取值（含历史短横线旧值）→ 回退为默认 FACT")
    void normalizeFallsBackToDefault() {
        assertThat(MemoryCategory.normalize(null)).isEqualTo("FACT");
        assertThat(MemoryCategory.normalize("")).isEqualTo("FACT");
        assertThat(MemoryCategory.normalize("summary")).isEqualTo("SUMMARY");
        // 历史遗留的存储层级值不应成为一个「类型」，归一为默认
        assertThat(MemoryCategory.normalize("long_term")).isEqualTo("FACT");
        assertThat(MemoryCategory.normalize("graph")).isEqualTo("FACT");
    }

    @Test
    @DisplayName("规范 token 恰好 5 个，isCanonical 正确")
    void tokensAndCanonical() {
        assertThat(MemoryCategory.tokens())
                .containsExactly("FACT", "PREFERENCE", "KNOWLEDGE", "HABIT", "SUMMARY");
        assertThat(MemoryCategory.isCanonical("summary")).isTrue();
        assertThat(MemoryCategory.isCanonical("Summary")).isTrue();
        assertThat(MemoryCategory.isCanonical("nope")).isFalse();
        assertThat(MemoryCategory.isCanonical(null)).isFalse();
    }
}
