package com.agi.assistant.controller;

import com.agi.assistant.model.dto.EvaluationTaskRequest;
import com.agi.assistant.model.entity.EvaluationResult;
import com.agi.assistant.model.entity.EvaluationTask;
import com.agi.assistant.model.vo.Result;
import com.agi.assistant.service.EvaluationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/evaluation")
@RequiredArgsConstructor
@Tag(name = "Evaluation", description = "评测系统接口")
public class EvaluationController {

    private final EvaluationService evaluationService;

    @PostMapping("/tasks")
    @Operation(summary = "创建评测任务", description = "创建一个新的RAG评测任务")
    public Result<EvaluationTask> createTask(
            @Valid @RequestBody EvaluationTaskRequest request,
            @Parameter(description = "用户ID") @RequestHeader("X-User-Id") Long userId) {
        log.info("Create evaluation task for user {}, name {}", userId, request.getName());
        EvaluationTask task = evaluationService.createTask(request, userId);
        return Result.ok(task);
    }

    @GetMapping("/tasks")
    @Operation(summary = "评测任务列表", description = "获取当前用户的评测任务列表")
    public Result<List<EvaluationTask>> listTasks(
            @Parameter(description = "用户ID") @RequestHeader("X-User-Id") Long userId) {
        log.info("List evaluation tasks for user {}", userId);
        List<EvaluationTask> tasks = evaluationService.listTasks(userId);
        return Result.ok(tasks);
    }

    @GetMapping("/tasks/{taskId}/results")
    @Operation(summary = "评测结果", description = "获取指定评测任务的结果列表")
    public Result<List<EvaluationResult>> getTaskResults(
            @Parameter(description = "任务ID") @PathVariable("taskId") Long taskId) {
        log.info("Get results for evaluation task {}", taskId);
        List<EvaluationResult> results = evaluationService.getTaskResults(taskId);
        return Result.ok(results);
    }

    @GetMapping("/compare")
    @Operation(summary = "对比评测结果", description = "对比两个评测任务的结果")
    public Result<Map<String, Object>> compareResults(
            @Parameter(description = "任务A的ID") @RequestParam("taskA") Long taskAId,
            @Parameter(description = "任务B的ID") @RequestParam("taskB") Long taskBId) {
        log.info("Compare evaluation tasks {} and {}", taskAId, taskBId);
        Map<String, Object> comparison = evaluationService.compareResults(taskAId, taskBId);
        return Result.ok(comparison);
    }
}
