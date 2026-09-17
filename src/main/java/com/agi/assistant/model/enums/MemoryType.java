package com.agi.assistant.model.enums;

/**
 * 记忆的<b>存储层级</b>（storage tier），<u>不是</u> {@code memory.type} 列的取值。
 * <p>
 * 重要澄清（历史坑）：本枚举曾一度被当作 {@code memory.type} 的取值来用，导致口径混乱 ——
 * 其中 {@code SHORT_TERM} 落在 Redis（{@code stm:} 前缀）、{@code RUNTIME} 落在运行态内存，
 * 这些值<b>从来不会</b>写进 {@code memory.type} 列。库里的 {@code SHORT_TERM/LONG_TERM/GRAPH/RUNTIME}
 * 实际上是「不存在的幽灵值」。
 * <p>
 * 因此职责已拆分：
 * <ul>
 *   <li>{@code memory.type} 列的语义类别 → 见 {@link MemoryCategory}
 *       （{@code FACT/PREFERENCE/KNOWLEDGE/HABIT/SUMMARY}）；</li>
 *   <li>本枚举仅表达「数据位于哪一层存储」，<b>不</b>作为 {@code type} 列的写入值。</li>
 * </ul>
 * 已 grep 确认：本枚举当前没有业务调用方（{@code LongTermMemory} 的默认 type 已改为
 * {@link MemoryCategory#DEFAULT}）。保留枚举是为了让「存储层级」这一概念仍有明确落点，
 * 避免后人再次把两个维度混为一谈；在未确认无外部消费者前不删除任何取值。
 *
 * @author Alex
 */
public enum MemoryType {

    /** 短期记忆：存入 Redis 的会话滑动窗口。 */
    SHORT_TERM,

    /** 长期记忆：落 MySQL {@code memory} 表 + 向量库。 */
    LONG_TERM,

    /** 图谱记忆：落 Neo4j 记忆节点与关系边。 */
    GRAPH,

    /** 运行时状态记忆：仅存在于进程内的运行态上下文。 */
    RUNTIME
}
