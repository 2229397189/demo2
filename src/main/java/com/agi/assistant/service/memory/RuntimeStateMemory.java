package com.agi.assistant.service.memory;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行时状态记忆层
 * <p>
 * 负责追踪 Agent 的运行时状态，包括：
 * - PlannerState: 当前计划、目标、多步推理状态
 * - ToolState: 工具调用历史、结果、待执行调用
 * - TaskMemory: 任务生命周期（创建→进行中→完成）
 */
@Slf4j
@Service
public class RuntimeStateMemory {

    /** 用户会话 -> PlannerState */
    private final ConcurrentHashMap<String, PlannerState> plannerStates = new ConcurrentHashMap<>();

    /** 用户会话 -> ToolState */
    private final ConcurrentHashMap<String, ToolState> toolStates = new ConcurrentHashMap<>();

    /** 用户会话 -> TaskMemory 列表 */
    private final ConcurrentHashMap<String, List<TaskMemory>> taskMemories = new ConcurrentHashMap<>();

    // ──────────────────────────────────────────────────────────────
    //  Planner State 管理
    // ──────────────────────────────────────────────────────────────

    /**
     * 获取或创建 PlannerState
     */
    public PlannerState getOrCreatePlannerState(String sessionId) {
        return plannerStates.computeIfAbsent(sessionId, id -> {
            log.debug("Creating new PlannerState for session [{}]", id);
            return new PlannerState(id);
        });
    }

    /**
     * 更新当前计划
     */
    public void updatePlan(String sessionId, List<String> steps) {
        PlannerState state = getOrCreatePlannerState(sessionId);
        state.setPlan(steps);
        state.setCurrentStepIndex(0);
        state.setUpdatedAt(LocalDateTime.now());
        log.debug("Updated plan for session [{}]: {} steps", sessionId, steps.size());
    }

    /**
     * 推进到下一步
     */
    public boolean advancePlanStep(String sessionId) {
        PlannerState state = getOrCreatePlannerState(sessionId);
        if (state.getPlan() == null || state.getPlan().isEmpty()) {
            return false;
        }
        int nextIndex = state.getCurrentStepIndex() + 1;
        if (nextIndex < state.getPlan().size()) {
            state.setCurrentStepIndex(nextIndex);
            state.setUpdatedAt(LocalDateTime.now());
            log.debug("Advanced to step {}/{} for session [{}]",
                    nextIndex + 1, state.getPlan().size(), sessionId);
            return true;
        }
        return false;
    }

    /**
     * 获取当前计划步骤
     */
    public String getCurrentPlanStep(String sessionId) {
        PlannerState state = getOrCreatePlannerState(sessionId);
        if (state.getPlan() == null || state.getPlan().isEmpty()) {
            return null;
        }
        int index = state.getCurrentStepIndex();
        if (index < state.getPlan().size()) {
            return state.getPlan().get(index);
        }
        return null;
    }

    /**
     * 标记计划完成
     */
    public void completePlan(String sessionId) {
        PlannerState state = getOrCreatePlannerState(sessionId);
        state.setStatus(PlanStatus.COMPLETED);
        state.setUpdatedAt(LocalDateTime.now());
        log.debug("Plan completed for session [{}]", sessionId);
    }

    // ──────────────────────────────────────────────────────────────
    //  Tool State 管理
    // ──────────────────────────────────────────────────────────────

    /**
     * 获取或创建 ToolState
     */
    public ToolState getOrCreateToolState(String sessionId) {
        return toolStates.computeIfAbsent(sessionId, id -> {
            log.debug("Creating new ToolState for session [{}]", id);
            return new ToolState(id);
        });
    }

    /**
     * 记录工具调用
     */
    public void recordToolCall(String sessionId, String toolName, Map<String, Object> params,
                               Object result, boolean success, long durationMs) {
        ToolState state = getOrCreateToolState(sessionId);
        ToolCallRecord record = new ToolCallRecord();
        record.setToolName(toolName);
        record.setParams(params);
        record.setResult(result);
        record.setSuccess(success);
        record.setDurationMs(durationMs);
        record.setTimestamp(LocalDateTime.now());

        state.getCallHistory().add(record);
        state.setLastUsedTool(toolName);
        state.setUpdatedAt(LocalDateTime.now());

        log.debug("Recorded tool call [{}] for session [{}]: success={}, duration={}ms",
                toolName, sessionId, success, durationMs);
    }

    /**
     * 添加待执行的工具调用
     */
    public void addPendingToolCall(String sessionId, String toolName, Map<String, Object> params) {
        ToolState state = getOrCreateToolState(sessionId);
        PendingToolCall pending = new PendingToolCall();
        pending.setToolName(toolName);
        pending.setParams(params);
        pending.setCreatedAt(LocalDateTime.now());

        state.getPendingCalls().add(pending);
        state.setUpdatedAt(LocalDateTime.now());
        log.debug("Added pending tool call [{}] for session [{}]", toolName, sessionId);
    }

    /**
     * 获取并移除下一个待执行的工具调用
     */
    public PendingToolCall pollPendingToolCall(String sessionId) {
        ToolState state = getOrCreateToolState(sessionId);
        if (state.getPendingCalls().isEmpty()) {
            return null;
        }
        PendingToolCall call = state.getPendingCalls().remove(0);
        state.setUpdatedAt(LocalDateTime.now());
        return call;
    }

    /**
     * 获取工具调用历史摘要
     */
    public String getToolCallSummary(String sessionId) {
        ToolState state = getOrCreateToolState(sessionId);
        if (state.getCallHistory().isEmpty()) {
            return "暂无工具调用记录";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("最近工具调用:\n");
        int start = Math.max(0, state.getCallHistory().size() - 5);
        for (int i = start; i < state.getCallHistory().size(); i++) {
            ToolCallRecord record = state.getCallHistory().get(i);
            sb.append(String.format("- %s: %s (%dms)\n",
                    record.getToolName(),
                    record.isSuccess() ? "成功" : "失败",
                    record.getDurationMs()));
        }
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────
    //  Task Memory 管理
    // ──────────────────────────────────────────────────────────────

    /**
     * 创建新任务
     */
    public TaskMemory createTask(String sessionId, String taskName, String description) {
        TaskMemory task = new TaskMemory();
        task.setId(UUID.randomUUID().toString());
        task.setSessionId(sessionId);
        task.setTaskName(taskName);
        task.setDescription(description);
        task.setStatus(TaskStatus.CREATED);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());

        taskMemories.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(task);
        log.debug("Created task [{}] for session [{}]: {}", task.getId(), sessionId, taskName);
        return task;
    }

    /**
     * 更新任务状态
     */
    public void updateTaskStatus(String sessionId, String taskId, TaskStatus status) {
        List<TaskMemory> tasks = taskMemories.get(sessionId);
        if (tasks == null) return;

        for (TaskMemory task : tasks) {
            if (task.getId().equals(taskId)) {
                task.setStatus(status);
                task.setUpdatedAt(LocalDateTime.now());
                if (status == TaskStatus.COMPLETED || status == TaskStatus.FAILED) {
                    task.setCompletedAt(LocalDateTime.now());
                }
                log.debug("Updated task [{}] status to {} for session [{}]",
                        taskId, status, sessionId);
                break;
            }
        }
    }

    /**
     * 更新任务进度
     */
    public void updateTaskProgress(String sessionId, String taskId, String progress) {
        List<TaskMemory> tasks = taskMemories.get(sessionId);
        if (tasks == null) return;

        for (TaskMemory task : tasks) {
            if (task.getId().equals(taskId)) {
                task.setCurrentProgress(progress);
                task.setUpdatedAt(LocalDateTime.now());
                break;
            }
        }
    }

    /**
     * 添加任务中间结果
     */
    public void addTaskResult(String sessionId, String taskId, String key, Object value) {
        List<TaskMemory> tasks = taskMemories.get(sessionId);
        if (tasks == null) return;

        for (TaskMemory task : tasks) {
            if (task.getId().equals(taskId)) {
                if (task.getResults() == null) {
                    task.setResults(new LinkedHashMap<>());
                }
                task.getResults().put(key, value);
                task.setUpdatedAt(LocalDateTime.now());
                break;
            }
        }
    }

    /**
     * 获取活跃任务列表
     */
    public List<TaskMemory> getActiveTasks(String sessionId) {
        List<TaskMemory> tasks = taskMemories.get(sessionId);
        if (tasks == null) return Collections.emptyList();

        return tasks.stream()
                .filter(t -> t.getStatus() == TaskStatus.CREATED || t.getStatus() == TaskStatus.IN_PROGRESS)
                .toList();
    }

    /**
     * 获取任务历史摘要
     */
    public String getTaskSummary(String sessionId) {
        List<TaskMemory> tasks = taskMemories.get(sessionId);
        if (tasks == null || tasks.isEmpty()) {
            return "暂无任务记录";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("任务历史:\n");
        int start = Math.max(0, tasks.size() - 5);
        for (int i = start; i < tasks.size(); i++) {
            TaskMemory task = tasks.get(i);
            sb.append(String.format("- [%s] %s: %s\n",
                    task.getStatus().getLabel(),
                    task.getTaskName(),
                    task.getCurrentProgress() != null ? task.getCurrentProgress() : ""));
        }
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────
    //  上下文组装
    // ──────────────────────────────────────────────────────────────

    /**
     * 组装运行时状态上下文
     */
    public String assembleRuntimeContext(String sessionId) {
        StringBuilder context = new StringBuilder();

        // Planner State
        PlannerState planner = plannerStates.get(sessionId);
        if (planner != null && planner.getPlan() != null && !planner.getPlan().isEmpty()) {
            context.append("## 当前计划状态\n");
            context.append(String.format("状态: %s\n", planner.getStatus().getLabel()));
            context.append("计划步骤:\n");
            for (int i = 0; i < planner.getPlan().size(); i++) {
                String marker = (i == planner.getCurrentStepIndex()) ? "→ " : "  ";
                context.append(String.format("%s%d. %s\n", marker, i + 1, planner.getPlan().get(i)));
            }
            context.append("\n");
        }

        // Tool State
        ToolState toolState = toolStates.get(sessionId);
        if (toolState != null && !toolState.getCallHistory().isEmpty()) {
            context.append("## 工具调用状态\n");
            context.append(getToolCallSummary(sessionId));
            if (!toolState.getPendingCalls().isEmpty()) {
                context.append("待执行调用: ").append(toolState.getPendingCalls().size()).append(" 个\n");
            }
            context.append("\n");
        }

        // Task Memory
        List<TaskMemory> tasks = taskMemories.get(sessionId);
        if (tasks != null && !tasks.isEmpty()) {
            context.append("## 任务状态\n");
            List<TaskMemory> activeTasks = getActiveTasks(sessionId);
            if (!activeTasks.isEmpty()) {
                context.append("活跃任务:\n");
                for (TaskMemory task : activeTasks) {
                    context.append(String.format("- %s [%s]: %s\n",
                            task.getTaskName(),
                            task.getStatus().getLabel(),
                            task.getCurrentProgress() != null ? task.getCurrentProgress() : "进行中"));
                }
            }
            context.append(getTaskSummary(sessionId));
        }

        return context.toString();
    }

    /**
     * 清理会话状态
     */
    public void clearSession(String sessionId) {
        plannerStates.remove(sessionId);
        toolStates.remove(sessionId);
        taskMemories.remove(sessionId);
        log.debug("Cleared runtime state for session [{}]", sessionId);
    }

    // ──────────────────────────────────────────────────────────────
    //  数据模型
    // ──────────────────────────────────────────────────────────────

    @Data
    public static class PlannerState {
        private final String sessionId;
        private List<String> plan;
        private int currentStepIndex = 0;
        private PlanStatus status = PlanStatus.PLANNING;
        private Map<String, Object> context = new ConcurrentHashMap<>();
        private LocalDateTime updatedAt = LocalDateTime.now();
    }

    @Data
    public static class ToolState {
        private final String sessionId;
        private final List<ToolCallRecord> callHistory = new ArrayList<>();
        private final List<PendingToolCall> pendingCalls = new ArrayList<>();
        private String lastUsedTool;
        private LocalDateTime updatedAt = LocalDateTime.now();
    }

    @Data
    public static class ToolCallRecord {
        private String toolName;
        private Map<String, Object> params;
        private Object result;
        private boolean success;
        private long durationMs;
        private LocalDateTime timestamp;
    }

    @Data
    public static class PendingToolCall {
        private String toolName;
        private Map<String, Object> params;
        private LocalDateTime createdAt;
    }

    @Data
    public static class TaskMemory {
        private String id;
        private String sessionId;
        private String taskName;
        private String description;
        private TaskStatus status;
        private String currentProgress;
        private Map<String, Object> results;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private LocalDateTime completedAt;
    }

    public enum PlanStatus {
        PLANNING("规划中"),
        EXECUTING("执行中"),
        COMPLETED("已完成"),
        FAILED("失败");

        private final String label;

        PlanStatus(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    public enum TaskStatus {
        CREATED("已创建"),
        IN_PROGRESS("进行中"),
        COMPLETED("已完成"),
        FAILED("失败"),
        CANCELLED("已取消");

        private final String label;

        TaskStatus(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }
}
