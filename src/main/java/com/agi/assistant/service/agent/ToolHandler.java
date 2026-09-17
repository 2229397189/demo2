package com.agi.assistant.service.agent;

import com.agi.assistant.model.dto.ToolResult;

import java.util.Map;

/**
 * 工具处理函数（强类型）。
 * <p>
 * 与旧的 {@code Function<Map<String,Object>, Map<String,Object>>} 相比，返回值为强类型的
 * {@link ToolResult}，可携带 {@code status}/{@code elapsedMs}/{@code truncated} 等语义。
 * {@code ToolRegistry} 会同时保留旧重载（内部适配为 {@link ToolResult#fromMap}），
 * 保证既有调用方零改动。
 *
 * @author Alex
 */
@FunctionalInterface
public interface ToolHandler {

    /**
     * 执行工具。
     *
     * @param params 入参（来自 LLM/调用方的参数 Map，可能为 null）
     * @return 统一结果 Schema，绝不返回 null
     */
    ToolResult handle(Map<String, Object> params);
}
