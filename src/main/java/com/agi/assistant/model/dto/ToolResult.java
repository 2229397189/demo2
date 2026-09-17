package com.agi.assistant.model.dto;

import com.agi.assistant.model.enums.ToolStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一工具结果 Schema。
 * <p>
 * 取代此前「靠 Map 键约定（{@code status}/{@code result}/{@code error}）」的松散返回方式：
 * {@link #toMap()} 仍输出旧调用方消费的键，{@link #fromMap(String, Map)} 兼容旧式
 * {@code Function<Map,Map>} handler，因此调用方可以零改动平滑迁移。
 * <p>
 * 状态语义：
 * <ul>
 *   <li>{@link ToolStatus#SUCCESS} —— 正常执行完成</li>
 *   <li>{@link ToolStatus#FAILURE} —— 执行失败，{@link #error} 非空</li>
 *   <li>{@link ToolStatus#TIMEOUT} —— 隔离执行超时被取消</li>
 *   <li>{@link ToolStatus#PARTIAL} —— 结果被截断，{@link #truncated} 为 true</li>
 * </ul>
 *
 * @author Alex
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolResult {

    /** 工具名。 */
    private String toolName;

    /** 执行状态。 */
    private ToolStatus status;

    /** 供 LLM 消费的可读文本（对应旧 Map 约定的 {@code result} 键）。 */
    private String content;

    /** 结构化负载（items/count/exitCode/elapsedMs 等）。 */
    private Map<String, Object> data;

    /** 失败原因（status != SUCCESS 时非空）。 */
    private String error;

    /** 耗时（毫秒）。 */
    private long elapsedMs;

    /** 结果是否被截断（PARTIAL 标记）。 */
    private boolean truncated;

    /** 截断原因。 */
    private String partialReason;

    /**
     * 构造一个成功结果。
     *
     * @param toolName 工具名
     * @param content  可读文本
     * @param data     结构化负载，可为 null（内部兜底为空 Map）
     * @return 状态为 {@link ToolStatus#SUCCESS} 的结果
     */
    public static ToolResult success(String toolName, String content, Map<String, Object> data) {
        return ToolResult.builder()
                .toolName(toolName)
                .status(ToolStatus.SUCCESS)
                .content(content)
                .data(data != null ? data : new LinkedHashMap<>())
                .elapsedMs(0L)
                .truncated(false)
                .build();
    }

    /**
     * 构造一个失败结果。
     *
     * @param toolName 工具名
     * @param error    失败原因
     * @return 状态为 {@link ToolStatus#FAILURE} 的结果
     */
    public static ToolResult failure(String toolName, String error) {
        return ToolResult.builder()
                .toolName(toolName)
                .status(ToolStatus.FAILURE)
                .error(error)
                .data(new LinkedHashMap<>())
                .elapsedMs(0L)
                .truncated(false)
                .build();
    }

    /**
     * 构造一个超时结果。
     *
     * @param toolName  工具名
     * @param elapsedMs 已消耗的毫秒数
     * @return 状态为 {@link ToolStatus#TIMEOUT} 的结果
     */
    public static ToolResult timeout(String toolName, long elapsedMs) {
        return ToolResult.builder()
                .toolName(toolName)
                .status(ToolStatus.TIMEOUT)
                .error("Tool execution timed out after " + elapsedMs + "ms")
                .data(new LinkedHashMap<>())
                .elapsedMs(elapsedMs)
                .truncated(false)
                .build();
    }

    /**
     * 就地把结果标记为「部分完成」（结果被截断）。
     *
     * @param reason 截断原因
     * @return this，便于链式调用
     */
    public ToolResult markPartial(String reason) {
        this.status = ToolStatus.PARTIAL;
        this.truncated = true;
        this.partialReason = reason;
        return this;
    }

    /**
     * 转换为兼容旧调用方的 Map。
     * <p>
     * 必输出键：{@code status}（枚举名，如 {@code SUCCESS}）、{@code result}（取自
     * {@link #content}）、{@code error}、{@code toolName}、{@code elapsedMs}、
     * {@code truncated}；{@link #partialReason} 与 {@link #data} 非空时一并输出。
     *
     * @return 键顺序稳定的 {@link LinkedHashMap}
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", status != null ? status.name() : ToolStatus.FAILURE.name());
        map.put("result", content);
        map.put("error", error);
        map.put("toolName", toolName);
        map.put("elapsedMs", elapsedMs);
        map.put("truncated", truncated);
        if (partialReason != null) {
            map.put("partialReason", partialReason);
        }
        if (data != null && !data.isEmpty()) {
            map.put("data", data);
        }
        return map;
    }

    /**
     * 从旧的 Map 结果反解为 {@link ToolResult}。
     *
     * @param toolName 工具名（旧 Map 未必携带，由调用方补全）
     * @param legacy   旧式结果 Map，可为 null
     * @return 归一化后的 {@link ToolResult}
     */
    @SuppressWarnings("unchecked")
    public static ToolResult fromMap(String toolName, Map<String, Object> legacy) {
        ToolResult result = new ToolResult();
        result.setToolName(toolName);
        result.setData(new LinkedHashMap<>());
        if (legacy == null) {
            result.setStatus(ToolStatus.SUCCESS);
            return result;
        }
        result.setStatus(parseStatus(legacy.get("status")));
        Object content = legacy.get("result");
        result.setContent(content != null ? String.valueOf(content) : null);
        Object error = legacy.get("error");
        result.setError(error != null ? String.valueOf(error) : null);
        result.setElapsedMs(parseLong(legacy.get("elapsedMs")));
        Object truncated = legacy.get("truncated");
        result.setTruncated(truncated != null && Boolean.parseBoolean(String.valueOf(truncated)));
        Object partialReason = legacy.get("partialReason");
        result.setPartialReason(partialReason != null ? String.valueOf(partialReason) : null);
        Object data = legacy.get("data");
        if (data instanceof Map) {
            result.setData((Map<String, Object>) data);
        }
        return result;
    }

    /**
     * 把旧式状态字符串归一化为 {@link ToolStatus}。
     * <p>
     * 兼容大小写与 {@code failed}/{@code error} 等历史写法；未知值保守地按
     * {@link ToolStatus#SUCCESS} 处理（旧约定里「无 status」即代表成功）。
     */
    private static ToolStatus parseStatus(Object raw) {
        if (raw == null) {
            return ToolStatus.SUCCESS;
        }
        String value = String.valueOf(raw).trim().toUpperCase();
        switch (value) {
            case "SUCCESS":
            case "OK":
                return ToolStatus.SUCCESS;
            case "FAILURE":
            case "FAILED":
            case "FAIL":
            case "ERROR":
                return ToolStatus.FAILURE;
            case "TIMEOUT":
            case "TIMED_OUT":
                return ToolStatus.TIMEOUT;
            case "PARTIAL":
                return ToolStatus.PARTIAL;
            default:
                return ToolStatus.SUCCESS;
        }
    }

    /**
     * 解析毫秒字段，无法解析时返回 0。
     */
    private static long parseLong(Object raw) {
        if (raw == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }
}
