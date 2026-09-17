package com.agi.assistant.model.enums;

/**
 * 工具执行状态。
 * <p>
 * 区分「从未执行」与「执行成功」：{@link #NOT_EXECUTED} 表示该工具注册后一次都没跑过。
 * 旧实现把注册初始状态硬编码为 {@link #SUCCESS}，导致 {@code GET /api/agent/tools}
 * 在一个工具从未执行时就上报 SUCCESS —— 一个纯粹的假状态。
 */
public enum ToolStatus {

    /** 已注册但从未执行过。 */
    NOT_EXECUTED,

    SUCCESS,

    FAILURE,

    TIMEOUT,

    PARTIAL
}
