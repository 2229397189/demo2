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
     * 获取用户的评测任务列表
     */
    List<EvaluationTask> listTasks(Long userId);

    /**
     * 获取评测任务的结果列表
     */
    List<EvaluationResult> getTaskResults(Long taskId);

    /**
     * 对比两个评测任务的结果
     */
    Map<String, Object> compareResults(Long taskAId, Long taskBId);
}
