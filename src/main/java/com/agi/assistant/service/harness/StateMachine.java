package com.agi.assistant.service.harness;

import com.agi.assistant.model.enums.TaskStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务状态机
 * <p>
 * 管理任务的状态转换，支持以下状态流：
 * <pre>
 * INITIALIZED → RUNNING → COMPLETED
 * INITIALIZED → RUNNING → FAILED → RETRYING → RUNNING
 * INITIALIZED → RUNNING → FAILED → FALLBACK → RUNNING
 * </pre>
 */
@Slf4j
@Component
public class StateMachine {

    /**
     * 合法的状态转换映射：from → allowed set of to
     */
    private static final Map<TaskStatus, Set<TaskStatus>> TRANSITIONS = Map.of(
            TaskStatus.INITIALIZED, Set.of(TaskStatus.RUNNING),
            TaskStatus.RUNNING, Set.of(TaskStatus.COMPLETED, TaskStatus.FAILED),
            TaskStatus.FAILED, Set.of(TaskStatus.RETRYING, TaskStatus.FALLBACK),
            TaskStatus.RETRYING, Set.of(TaskStatus.RUNNING),
            TaskStatus.FALLBACK, Set.of(TaskStatus.RUNNING)
    );

    /** 任务名称 → 当前状态 */
    private final Map<String, TaskStatus> taskStates = new ConcurrentHashMap<>();

    /**
     * 执行状态转换。
     * <p>
     * 若转换不合法，抛出 {@link IllegalStateException}。
     *
     * @param taskName    任务名称
     * @param targetState 目标状态
     * @return 转换后的新状态
     * @throws IllegalStateException 如果状态转换不合法
     */
    public TaskStatus transition(String taskName, TaskStatus targetState) {
        TaskStatus currentState = taskStates.getOrDefault(taskName, TaskStatus.INITIALIZED);

        if (!canTransition(currentState, targetState)) {
            throw new IllegalStateException(
                    String.format("Invalid state transition for task [%s]: %s -> %s",
                            taskName, currentState, targetState));
        }

        taskStates.put(taskName, targetState);
        log.info("Task [{}] state transition: {} -> {}", taskName, currentState, targetState);
        return targetState;
    }

    /**
     * 检查是否可以从 from 状态转换到 to 状态。
     *
     * @param from 当前状态
     * @param to   目标状态
     * @return true 如果转换合法
     */
    public boolean canTransition(TaskStatus from, TaskStatus to) {
        Set<TaskStatus> allowed = TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    /**
     * 获取指定任务的当前状态。
     *
     * @param taskName 任务名称
     * @return 当前状态，未注册的任务返回 INITIALIZED
     */
    public TaskStatus getStatus(String taskName) {
        return taskStates.getOrDefault(taskName, TaskStatus.INITIALIZED);
    }

    /**
     * 重置指定任务的状态为 INITIALIZED。
     *
     * @param taskName 任务名称
     */
    public void reset(String taskName) {
        taskStates.put(taskName, TaskStatus.INITIALIZED);
        log.debug("Task [{}] state reset to INITIALIZED", taskName);
    }

    /**
     * 移除指定任务的状态记录。
     *
     * @param taskName 任务名称
     */
    public void remove(String taskName) {
        taskStates.remove(taskName);
        log.debug("Task [{}] removed from state machine", taskName);
    }

    /**
     * 获取所有任务的状态快照。
     *
     * @return 不可变的任务状态映射
     */
    public Map<String, TaskStatus> getAllStatuses() {
        return Map.copyOf(taskStates);
    }
}
