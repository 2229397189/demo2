package com.agi.assistant.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「按类型筛选记忆」口径统一的单测。
 * <p>
 * 背景缺陷：此前 {@code memory.type} 存在「存储层枚举 / LLM 抽取词 / 前端 tab」三方口径不一致，
 * 筛选把输入原样拼进 where，大小写或取值任何一处对不上就永远返回空数组（HTTP 200）。
 * <p>
 * 修复：筛选入口把输入归一为规范大写 token（见 {@code MemoryServiceImpl.normalizeMemoryType}），
 * 再与 {@code UPPER(type)} 比较，故大小写与历史取值都不再影响命中。
 * <p>
 * 说明：本测试直接断言「归一化纯方法」的行为（这是筛选正确性的核心契约），
 * 而不去反射读取 MyBatis-Plus wrapper 内部结构 —— 后者的 {@code apply("...{0}", val)}
 * 参数并不进入 {@code paramNameValuePairs}，且 {@code getSqlSegment()} 在纯单测环境会因
 * lambda 缓存未初始化而报错，属框架限制，不适合作为稳定断言点。
 * 归一化本身的完整覆盖见 {@code MemoryCategoryTest}。
 *
 * @author Alex
 */
class MemoryTypeFilterTest {

    @Test
    @DisplayName("fact / FACT / Fact 三种写法归一后完全一致（大写 FACT）")
    void typeFilterIsCaseInsensitive() {
        assertThat(MemoryServiceImpl.normalizeMemoryType("fact")).isEqualTo("FACT");
        assertThat(MemoryServiceImpl.normalizeMemoryType("FACT")).isEqualTo("FACT");
        assertThat(MemoryServiceImpl.normalizeMemoryType("Fact")).isEqualTo("FACT");
        assertThat(MemoryServiceImpl.normalizeMemoryType(" preference ")).isEqualTo("PREFERENCE");
        assertThat(MemoryServiceImpl.normalizeMemoryType("knowledge")).isEqualTo("KNOWLEDGE");
        assertThat(MemoryServiceImpl.normalizeMemoryType("habit")).isEqualTo("HABIT");
        assertThat(MemoryServiceImpl.normalizeMemoryType("summary")).isEqualTo("SUMMARY");
    }

    @Test
    @DisplayName("历史遗留值 / null / 空 一律回退到规范默认 FACT，绝不产生非规范 token")
    void unknownOrBlankFallsBackToDefault() {
        // 历史存储层旧值不是「语义类别」，归一后必须落回规范值而非原样透传
        assertThat(MemoryServiceImpl.normalizeMemoryType("long_term")).isEqualTo("FACT");
        assertThat(MemoryServiceImpl.normalizeMemoryType("graph")).isEqualTo("FACT");
        assertThat(MemoryServiceImpl.normalizeMemoryType("short_term")).isEqualTo("FACT");
        assertThat(MemoryServiceImpl.normalizeMemoryType(null)).isEqualTo("FACT");
        assertThat(MemoryServiceImpl.normalizeMemoryType("")).isEqualTo("FACT");
        assertThat(MemoryServiceImpl.normalizeMemoryType("   ")).isEqualTo("FACT");
    }
}
