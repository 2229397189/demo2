package com.agi.assistant.service.agent;

import com.agi.assistant.model.dto.ToolResult;
import com.agi.assistant.model.enums.ToolRiskLevel;
import com.agi.assistant.model.enums.ToolStatus;
import com.agi.assistant.service.security.AuditService;
import com.agi.assistant.service.security.ToolRiskClassifier;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Registry of available tools for agent execution.
 * <p>
 * Each tool has a name, description, risk level, and handler function.
 * Supports registration, lookup, listing, and safe execution with
 * risk-level-based access control.
 */
@Slf4j
@Component
public class ToolRegistry {

    private final Map<String, ToolDefinition> tools = new ConcurrentHashMap<>();
    private final ToolRiskClassifier toolRiskClassifier;
    private final AuditService auditService;
    private final ToolExecutorService toolExecutorService;

    public ToolRegistry(ToolRiskClassifier toolRiskClassifier,
                        @org.springframework.context.annotation.Lazy AuditService auditService,
                        ToolExecutorService toolExecutorService) {
        this.toolRiskClassifier = toolRiskClassifier;
        this.auditService = auditService;
        this.toolExecutorService = toolExecutorService;
    }

    // ----------------------------------------------------------------
    //  Public API
    // ----------------------------------------------------------------

    /**
     * Register a tool in the registry (Legacy signature).
     * <p>
     * Kept for backward compatibility: the old-style {@code Function<Map,Map>} handler
     * is adapted into a {@link ToolHandler} by wrapping its result via
     * {@link ToolResult#fromMap(String, Map)}. Existing callers keep compiling unchanged.
     *
     * @param name        unique tool name
     * @param description human-readable description of what the tool does
     * @param riskLevel   the risk level (SAFE, WARN, BLOCK)
     * @param handler     the legacy function that executes the tool
     */
    public void registerTool(String name, String description, ToolRiskLevel riskLevel,
                             Function<Map<String, Object>, Map<String, Object>> handler) {
        if (handler == null) {
            throw new IllegalArgumentException("Tool handler must not be null");
        }
        // 显式转型到 ToolHandler：否则与下面的强类型重载构成重载歧义
        registerTool(name, description, riskLevel,
                (ToolHandler) params -> ToolResult.fromMap(name, handler.apply(params)));
    }

    /**
     * Register a tool in the registry (Strongly-typed signature).
     *
     * @param name        unique tool name
     * @param description human-readable description of what the tool does
     * @param riskLevel   the risk level (SAFE, WARN, BLOCK)
     * @param handler     the {@link ToolHandler} that executes the tool and returns a {@link ToolResult}
     */
    public void registerTool(String name, String description, ToolRiskLevel riskLevel,
                             ToolHandler handler) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tool name must not be null or blank");
        }
        if (handler == null) {
            throw new IllegalArgumentException("Tool handler must not be null");
        }

        ToolDefinition tool = ToolDefinition.builder()
                .name(name)
                .description(description != null ? description : "")
                .riskLevel(riskLevel != null ? riskLevel : ToolRiskLevel.SAFE)
                .handler(handler)
                // 注册即「从未执行」：旧实现硬编码 SUCCESS，导致 GET /api/agent/tools
                // 在一个工具一次都没跑过时就上报 SUCCESS（纯假状态）。
                .status(ToolStatus.NOT_EXECUTED)
                .build();

        tools.put(name.toLowerCase(), tool);
        log.info("Registered tool: name={}, riskLevel={}", name, tool.getRiskLevel());
    }

    /**
     * Get a tool definition by name.
     *
     * @param name the tool name
     * @return the tool definition, or null if not found
     */
    public ToolDefinition getTool(String name) {
        if (name == null) {
            return null;
        }
        return tools.get(name.toLowerCase());
    }

    /**
     * List all registered tools.
     *
     * @return unmodifiable list of all tool definitions
     */
    public List<ToolDefinition> listTools() {
        return Collections.unmodifiableList(new ArrayList<>(tools.values()));
    }

    /**
     * List tools filtered by risk level.
     *
     * @param maxRiskLevel the maximum acceptable risk level
     * @return list of tools with risk level at or below the threshold
     */
    public List<ToolDefinition> listToolsByRisk(ToolRiskLevel maxRiskLevel) {
        if (maxRiskLevel == null) {
            return listTools();
        }

        List<ToolDefinition> filtered = new ArrayList<>();
        for (ToolDefinition tool : tools.values()) {
            if (tool.getRiskLevel().ordinal() <= maxRiskLevel.ordinal()) {
                filtered.add(tool);
            }
        }
        return filtered;
    }

    /**
     * Execute a tool by name with the given parameters (legacy compatible).
     * <p>
     * Delegates to {@link #executeToolTyped(String, Map, Long)} and converts the
     * strongly-typed {@link ToolResult} back into the legacy Map shape
     * (keys {@code status}/{@code result}/{@code error}/{@code toolName}/{@code elapsedMs}),
     * so existing callers (ReactEngine / DAGScheduler / AgentController) compile and
     * behave identically.
     *
     * @param name   the tool name
     * @param params the execution parameters
     * @return a result map containing "status", "result", and "toolName"
     */
    public Map<String, Object> executeTool(String name, Map<String, Object> params) {
        return executeTool(name, params, null);
    }

    /**
     * 带调用者身份的版本（legacy compatible）：命中审计时会记录发起人。
     *
     * @param name   工具名
     * @param params 参数
     * @param userId 调用者 ID，可为 null
     * @return 兼容旧调用方的结果 Map
     */
    public Map<String, Object> executeTool(String name, Map<String, Object> params, Long userId) {
        return executeToolTyped(name, params, userId).toMap();
    }

    /**
     * 强类型执行入口。
     * <p>
     * 执行前做风险判定（见下方注释），执行时<b>委托 {@link ToolExecutorService} 按类别隔离执行</b>：
     * 工具运行在所属类别的有界池上，超出 {@code harness.timeout.tool-timeout} 即被取消并返回
     * {@link ToolStatus#TIMEOUT}；执行异常也归一为结构化 {@link ToolResult#failure}，不向上抛。
     *
     * @param name   工具名
     * @param params 参数
     * @param userId 调用者 ID，可为 null
     * @return 统一 {@link ToolResult}，绝不返回 null
     */
    public ToolResult executeToolTyped(String name, Map<String, Object> params, Long userId) {
        ToolDefinition tool = getTool(name);
        if (tool == null) {
            log.warn("Tool not found: {}", name);
            return ToolResult.failure(name, "Tool not found: " + name);
        }

        // 风险判定：
        //   基础风险 = 工具注册时自己声明的 riskLevel（自研工具自己最清楚风险）
        //   参数风险 = 分类器只看参数：命中「高置信度危险特征」（rm -rf / drop table /
        //              curl|sh / wget|sh / mkfs / dd if= / chmod 777 / :(){ :|:& };: 等）
        //              时升级为 BLOCK；命中较宽泛的可疑特征时仅升级为 WARN
        //   名称黑名单 = 硬红线，命中即阻断（即使被误注册）
        // 变更说明：此前 classifyParamsOnly 把参数风险封顶在 WARN，导致下面的 BLOCK
        // 拦截分支在生产环境根本不可达；把「高置信度危险参数」升级为 BLOCK 后，
        // 该分支才真正生效（并顺带触发被阻断调用的审计）。
        // 注意：仍不调用 toolRiskClassifier.classify(name, paramsStr) —— 那会把所有
        // 「自声明为 SAFE 的自研工具」因「未知名称一律 WARN」而误判成 WARN，每次调用
        // 都刷一条 "Executing tool with WARN risk level"，噪音淹没真信号。
        // 已注册工具以自声明风险为准，仅在参数出现高置信危险特征时升级为 BLOCK。
        String paramsStr = params != null ? params.toString() : null;
        ToolRiskLevel paramRisk = toolRiskClassifier.classifyParamsOnly(paramsStr);
        ToolRiskLevel effectiveRisk = paramRisk.ordinal() > tool.getRiskLevel().ordinal()
                ? paramRisk : tool.getRiskLevel();
        if (toolRiskClassifier.isBlockedName(name)) {
            effectiveRisk = ToolRiskLevel.BLOCK;
        }

        log.debug("Tool [{}] risk check: registered={}, paramRisk={}, effective={}",
                name, tool.getRiskLevel(), paramRisk, effectiveRisk);

        // Risk level check
        switch (effectiveRisk) {
            case BLOCK:
                log.warn("Tool [{}] is BLOCKED, refusing execution", name);
                // 被阻断的调用是安全事件，必须留痕
                audit(userId, name, effectiveRisk, true, "blocked by risk classifier: " + paramsStr);
                return ToolResult.failure(name, "Tool is blocked: " + name);

            case WARN:
                log.warn("Executing tool [{}] with WARN risk level", name);
                break;

            case SAFE:
            default:
                log.debug("Executing tool [{}]: riskLevel={}", name, tool.getRiskLevel());
                break;
        }

        // 委托隔离执行：按工具类别提交到有界池，超时返回 TIMEOUT，异常返回 FAILURE
        long start = System.currentTimeMillis();
        ToolResult result = toolExecutorService.executeIsolated(
                name, () -> tool.getHandler().handle(params), toolExecutorService.getDefaultTimeoutMs());
        long elapsed = System.currentTimeMillis() - start;

        if (result == null) {
            result = ToolResult.failure(name, "Tool returned a null result");
        }
        if (result.getStatus() == null) {
            result.setStatus(ToolStatus.FAILURE);
        }
        if (result.getElapsedMs() <= 0) {
            result.setElapsedMs(elapsed);
        }

        tool.setStatus(result.getStatus());
        tool.setLastExecutedAt(LocalDateTime.now());

        if (result.getStatus() == ToolStatus.SUCCESS) {
            log.info("Tool [{}] executed successfully in {}ms", name, elapsed);
            audit(userId, name, effectiveRisk, false, "ok in " + elapsed + "ms");
        } else {
            log.warn("Tool [{}] finished with status {} in {}ms: {}",
                    name, result.getStatus(), elapsed, result.getError());
            audit(userId, name, effectiveRisk, false, result.getStatus() + ": " + result.getError());
        }
        return result;
    }

    /**
     * 记录工具调用审计。审计失败绝不能影响工具调用本身。
     */
    private void audit(Long userId, String toolName, ToolRiskLevel riskLevel,
                       boolean blocked, String details) {
        if (auditService == null) {
            return;
        }
        try {
            auditService.log(userId, blocked ? "TOOL_BLOCKED" : "TOOL_EXECUTE",
                    "tool:" + toolName, riskLevel, blocked, details);
        } catch (Exception e) {
            log.debug("Failed to write tool audit log: {}", e.getMessage());
        }
    }

    /**
     * Unregister a tool from the registry.
     *
     * @param name the tool name
     * @return true if the tool was removed, false if not found
     */
    public boolean unregisterTool(String name) {
        if (name == null) {
            return false;
        }
        ToolDefinition removed = tools.remove(name.toLowerCase());
        if (removed != null) {
            log.info("Unregistered tool: {}", name);
            return true;
        }
        return false;
    }

    /**
     * Get the number of registered tools.
     */
    public int size() {
        return tools.size();
    }

    /**
     * Check if a tool is registered.
     *
     * @param name the tool name
     * @return true if registered
     */
    public boolean hasTool(String name) {
        return name != null && tools.containsKey(name.toLowerCase());
    }

    // ----------------------------------------------------------------
    //  Inner Classes
    // ----------------------------------------------------------------

    /**
     * Definition of a registered tool.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolDefinition {

        /**
         * Unique tool name.
         */
        private String name;

        /**
         * Human-readable description.
         */
        private String description;

        /**
         * Risk level for access control.
         */
        private ToolRiskLevel riskLevel;

        /**
         * The handler that executes the tool.
         * Input: parameter map. Output: strongly-typed {@link ToolResult}.
         */
        private ToolHandler handler;

        /**
         * Last execution status.
         */
        private ToolStatus status;

        /**
         * Timestamp of last execution.
         */
        private LocalDateTime lastExecutedAt;
    }
}
