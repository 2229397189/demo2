package com.agi.assistant.service;

import com.agi.assistant.model.dto.EvaluationTaskRequest;
import com.agi.assistant.model.entity.EvaluationResult;
import com.agi.assistant.model.entity.EvaluationTask;

import java.util.List;
import java.util.Map;

public interface EvaluationService {

    /**
     * 创建评测任务
     */
    EvaluationTask createTask(EvaluationTaskRequest request, Long userId);

    /**
     * 运行指定评测任务（异步执行），供前端「运行」按钮调用。
     * <p>
     * 不会重复触发：若任务处于 RUNNING 状态则直接返回当前任务。
     *
     * @param taskId 任务 ID
     * @return 触发后的任务对象（状态应为 RUNNING）
     */
    EvaluationTask runTask(Long taskId);

    /**
     * 获取用户的评测任务列表
     */
    List<EvaluationTask> listTasks(Long userId);

    /**
     * 获取评测任务的结果列表。
     * <p>
     * 会校验任务归属：{@code userId} 与任务所有者不一致时抛
     * {@code AccessDeniedException}（HTTP 403），既不返回别人的数据也不静默返回空。
     *
     * @param taskId 任务 ID
     * @param userId 当前登录用户
     * @return 结果列表
     */
    List<EvaluationResult> getTaskResults(Long taskId, Long userId);

    /**
     * 对比两个评测任务的结果。
     * <p>
     * 两个任务都必须属于 {@code userId}，否则抛 {@code AccessDeniedException}。
     *
     * @param taskAId 任务 A 的 ID
     * @param taskBId 任务 B 的 ID
     * @param userId  当前登录用户
     * @return 对比结果
     */
    Map<String, Object> compareResults(Long taskAId, Long taskBId, Long userId);
}
