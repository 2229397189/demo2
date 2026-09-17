package com.agi.assistant.service.agent;

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

    public ToolRegistry(ToolRiskClassifier toolRiskClassifier,
                        @org.springframework.context.annotation.Lazy AuditService auditService) {
        this.toolRiskClassifier = toolRiskClassifier;
        this.auditService = auditService;
    }

    // ----------------------------------------------------------------
    //  Public API
    // ----------------------------------------------------------------

    /**
     * Register a tool in the registry.
     *
     * @param name        unique tool name
     * @param description human-readable description of what the tool does
     * @param riskLevel   the risk level (SAFE, WARN, BLOCK)
     * @param handler     the function that executes the tool
     */
    public void registerTool(String name, String description, ToolRiskLevel riskLevel,
                             Function<Map<String, Object>, Map<String, Object>> handler) {
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
                .status(ToolStatus.SUCCESS)
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
     * Execute a tool by name with the given parameters.
     * <p>
     * Performs risk-level checking before execution:
     * - SAFE: executes without restriction
     * - WARN: executes but logs a warning
     * - BLOCK: rejects execution
     *
     * @param name   the tool name
     * @param params the execution parameters
     * @return a result map containing "status", "result", and "toolName"
     * @throws IllegalArgumentException if the tool is not found
     * @throws SecurityException        if the tool is blocked by risk level
     */
    public Map<String, Object> executeTool(String name, Map<String, Object> params) {
        return executeTool(name, params, null);
    }

    /**
     * 带调用者身份的版本：命中审计时会记录发起人。
     *
     * @param name   工具名
     * @param params 参数
     * @param userId 调用者 ID，可为 null
     */
    public Map<String, Object> executeTool(String name, Map<String, Object> params, Long userId) {
        ToolDefinition tool = getTool(name);
        if (tool == null) {
            log.warn("Tool not found: {}", name);
            Map<String, Object> errorResult = new ConcurrentHashMap<>();
            errorResult.put("status", ToolStatus.FAILURE.name());
            errorResult.put("error", "Tool not found: " + name);
            errorResult.put("toolName", name);
            return errorResult;
        }

        // 风险判定：
        //   基础风险 = 工具注册时自己声明的 riskLevel（自研工具自己最清楚风险）
        //   参数风险 = 分类器只看参数，发现注入/危险命令时把等级往上抬
        //   名称黑名单 = 硬红线，命中即阻断（即使被误注册）
        // 修复说明：此前用 toolRiskClassifier.classify(name, paramsStr) 把「名称风险」
        // 也算进来，而分类器对未知名称一律返回 WARN —— 所有自研工具都会被抬到 WARN，
        // 每次调用都刷一条 "Executing tool with WARN risk level"，噪音淹没真信号。
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
                Map<String, Object> blockedResult = new ConcurrentHashMap<>();
                blockedResult.put("status", ToolStatus.FAILURE.name());
                blockedResult.put("error", "Tool is blocked: " + name);
                blockedResult.put("toolName", name);
                // 被阻断的调用是安全事件，必须留痕
                audit(userId, name, effectiveRisk, true, "blocked by risk classifier: " + paramsStr);
                return blockedResult;

            case WARN:
                log.warn("Executing tool [{}] with WARN risk level", name);
                break;

            case SAFE:
            default:
                log.debug("Executing tool [{}]: riskLevel={}", name, tool.getRiskLevel());
                break;
        }

        // Execute the tool
        long startTime = System.currentTimeMillis();
        try {
            Map<String, Object> result = tool.getHandler().apply(params);
            long elapsed = System.currentTimeMillis() - startTime;

            if (result == null) {
                result = new ConcurrentHashMap<>();
            }

            result.put("status", ToolStatus.SUCCESS.name());
            result.put("toolName", name);
            result.put("elapsedMs", elapsed);

            tool.setStatus(ToolStatus.SUCCESS);
            tool.setLastExecutedAt(LocalDateTime.now());

            log.info("Tool [{}] executed successfully in {}ms", name, elapsed);
            audit(userId, name, effectiveRisk, false, "ok in " + elapsed + "ms");
            return result;

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("Tool [{}] execution failed after {}ms: {}", name, elapsed, e.getMessage(), e);

            tool.setStatus(ToolStatus.FAILURE);
            tool.setLastExecutedAt(LocalDateTime.now());

            Map<String, Object> failureResult = new ConcurrentHashMap<>();
            failureResult.put("status", ToolStatus.FAILURE.name());
            failureResult.put("error", e.getMessage());
            failureResult.put("toolName", name);
            failureResult.put("elapsedMs", elapsed);
            audit(userId, name, effectiveRisk, false, "failed: " + e.getMessage());
            return failureResult;
        }
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
         * The handler function that executes the tool.
         * Input: parameter map. Output: result map.
         */
        private Function<Map<String, Object>, Map<String, Object>> handler;

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
