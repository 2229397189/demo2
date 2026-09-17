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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/evaluation")
@RequiredArgsConstructor
@Tag(name = "Evaluation", description = "评测系统接口")
public class EvaluationController {

    private final EvaluationService evaluationService;
    private final com.agi.assistant.service.evaluation.BenchmarkDataset benchmarkDataset;

    @GetMapping("/datasets")
    @Operation(summary = "可用数据集列表", description = "列出所有可用的评测数据集及其查询数量")
    public Result<List<Map<String, Object>>> listDatasets() {
        return Result.ok(benchmarkDataset.listDatasets());
    }

    @PostMapping("/tasks")
    @Operation(summary = "创建评测任务", description = "创建一个新的RAG评测任务")
    public Result<EvaluationTask> createTask(
            @Valid @RequestBody EvaluationTaskRequest request,
            @Parameter(description = "用户ID") @RequestHeader(value = "X-User-Id", required = false, defaultValue = "1") Long userId) {
        log.info("Create evaluation task for user {}, name {}", userId, request.getName());
        EvaluationTask task = evaluationService.createTask(request, userId);
        return Result.ok(task);
    }

    @GetMapping("/tasks")
    @Operation(summary = "评测任务列表", description = "获取当前用户的评测任务列表")
    public Result<List<EvaluationTask>> listTasks(
            @Parameter(description = "用户ID") @RequestHeader(value = "X-User-Id", required = false, defaultValue = "1") Long userId) {
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

    @PostMapping("/tasks/{taskId}/run")
    @Operation(summary = "运行评测任务", description = "触发指定评测任务的异步执行，供前端「运行」按钮调用")
    public Result<EvaluationTask> runTask(
            @Parameter(description = "任务ID") @PathVariable("taskId") Long taskId) {
        log.info("Run evaluation task {}", taskId);
        EvaluationTask task = evaluationService.runTask(taskId);
        return Result.ok(task);
    }

    @PostMapping("/datasets/import")
    @Operation(summary = "从已上传文档构建数据集", description = "用已完成上传的文档生成 golden query 数据集")
    public Result<Map<String, Object>> importDatasetFromDocuments(
            @Parameter(description = "数据集ID") @RequestParam("datasetId") String datasetId,
            @Parameter(description = "最多使用的文档数") @RequestParam(value = "limit", defaultValue = "4") int limit) {
        log.info("Import dataset [{}] from documents, limit={}", datasetId, limit);
        int imported = benchmarkDataset.importFromDocuments(datasetId, limit);
        Map<String, Object> result = new HashMap<>();
        result.put("datasetId", datasetId);
        result.put("imported", imported);
        return Result.ok(result);
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
