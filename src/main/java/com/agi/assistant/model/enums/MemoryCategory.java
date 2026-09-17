package com.agi.assistant.model.enums;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * {@code memory.type} 列的<b>权威语义类别定义</b>（单一事实来源）。
 * <p>
 * 背景：此前 {@code memory.type} 存在「三方口径不一致」——存储层枚举 {@link MemoryType} 是
 * {@code SHORT_TERM/LONG_TERM/GRAPH/RUNTIME}（其中 3 个在库里永远不存在），LLM 抽取 prompt 产出
 * 的却是 {@code preference/knowledge/fact/habit}，而按类型筛选又把 {@code type} 原样拼进 where
 * 子句 —— 大小写或取值任何一处对不上，筛选就永远返回空数组（HTTP 200）。本枚举把口径收敛到
 * <b>一处</b>，禁止 5 个字符串再散落在各处。
 * <p>
 * 规范取值是 5 个大写 token：{@link #FACT} / {@link #PREFERENCE} / {@link #KNOWLEDGE} /
 * {@link #HABIT} / {@link #SUMMARY}。
 * <ul>
 *   <li><b>写入</b>：任何保存路径都必须经过 {@link #normalize(String)} 归一为大写 token；</li>
 *   <li><b>读取/筛选</b>：输入先 {@link #normalize(String)} 再与 {@code UPPER(type)} 比较，
 *       保证历史小写数据也能被筛出来；</li>
 *   <li><b>LLM 抽取</b>：prompt 只允许产出这 5 个 token 之一（见 MemoryConsolidation 的 prompt 文本）。</li>
 * </ul>
 * 注意：本枚举描述的是「语义类别」，与 {@link MemoryType}（存储层级）是<b>两个维度</b>，切勿混用。
 *
 * @author Alex
 */
public enum MemoryCategory {

    /** 用户明确陈述的客观事实。 */
    FACT("用户明确陈述的客观事实"),

    /** 用户的偏好 / 喜好。 */
    PREFERENCE("用户的偏好或喜好"),

    /** 用户掌握的知识或技能。 */
    KNOWLEDGE("用户掌握的知识或技能"),

    /** 用户的习惯 / 惯常行为。 */
    HABIT("用户的习惯或惯常行为"),

    /** 对一段对话历史的归纳摘要。 */
    SUMMARY("对一段对话历史的归纳摘要");

    /** 未知 / 缺失类型的兜底类别。 */
    public static final MemoryCategory DEFAULT = FACT;

    private final String description;

    MemoryCategory(String description) {
        this.description = description;
    }

    /**
     * 语义类别的中文说明（用于 prompt 与展示）。
     *
     * @return 中文描述
     */
    public String getDescription() {
        return description;
    }

    /**
     * 把任意输入归一化为规范 token。
     * <p>
     * 规则：去空白 → 转大写 → 命中 5 个规范值之一则返回之；否则（null / 空 / 未知取值，
     * 例如历史遗留的 {@code short_term}）回退为 {@link #DEFAULT}。这样即使 LLM 自由发挥，
     * 落库的 {@code type} 也始终是 5 个规范 token 之一。
     *
     * @param raw 原始输入，可为 null
     * @return 规范 token（大写，必为 5 个之一）
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT.name();
        }
        String token = raw.trim().toUpperCase(Locale.ROOT);
        for (MemoryCategory category : values()) {
            if (category.name().equals(token)) {
                return token;
            }
        }
        return DEFAULT.name();
    }

    /**
     * 判断输入是否为规范取值（大小写不敏感）。
     *
     * @param raw 原始输入
     * @return true 表示归一化后仍然是 5 个规范 token 之一（即输入本身合法）
     */
    public static boolean isCanonical(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String token = raw.trim().toUpperCase(Locale.ROOT);
        for (MemoryCategory category : values()) {
            if (category.name().equals(token)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 5 个规范 token，供 prompt 生成 / 校验复用。
     *
     * @return 规范 token 列表（不可变）
     */
    public static List<String> tokens() {
        return Arrays.stream(values())
                .map(MemoryCategory::name)
                .collect(Collectors.toUnmodifiableList());
    }
}
