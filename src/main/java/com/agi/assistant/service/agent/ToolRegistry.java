package com.agi.assistant.service.agent;

import com.agi.assistant.model.enums.ToolRiskLevel;
import com.agi.assistant.model.enums.ToolStatus;
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

    public ToolRegistry(ToolRiskClassifier toolRiskClassifier) {
        this.toolRiskClassifier = toolRiskClassifier;
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
        ToolDefinition tool = getTool(name);
        if (tool == null) {
            log.warn("Tool not found: {}", name);
            Map<String, Object> errorResult = new ConcurrentHashMap<>();
            errorResult.put("status", ToolStatus.FAILURE.name());
            errorResult.put("error", "Tool not found: " + name);
            errorResult.put("toolName", name);
            return errorResult;
        }

        // 使用 ToolRiskClassifier 进行动态风险分类
        String paramsStr = params != null ? params.toString() : null;
        ToolRiskLevel classifiedRisk = toolRiskClassifier.classify(name, paramsStr);
        ToolRiskLevel effectiveRisk = classifiedRisk.ordinal() > tool.getRiskLevel().ordinal()
                ? classifiedRisk : tool.getRiskLevel();

        log.debug("Tool [{}] risk check: registered={}, classified={}, effective={}",
                name, tool.getRiskLevel(), classifiedRisk, effectiveRisk);

        // Risk level check
        switch (effectiveRisk) {
            case BLOCK:
                log.warn("Tool [{}] is BLOCKED, refusing execution", name);
                Map<String, Object> blockedResult = new ConcurrentHashMap<>();
                blockedResult.put("status", ToolStatus.FAILURE.name());
                blockedResult.put("error", "Tool is blocked: " + name);
                blockedResult.put("toolName", name);
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

            log.info("Tool [{}] executed successfully in {}ms", name, elapsed);
            return result;

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("Tool [{}] execution failed after {}ms: {}", name, elapsed, e.getMessage(), e);

            Map<String, Object> failureResult = new ConcurrentHashMap<>();
            failureResult.put("status", ToolStatus.FAILURE.name());
            failureResult.put("error", e.getMessage());
            failureResult.put("toolName", name);
            failureResult.put("elapsedMs", elapsed);
            return failureResult;
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
