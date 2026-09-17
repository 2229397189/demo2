package com.agi.assistant.controller;

import com.agi.assistant.model.dto.EvaluationSnapshot;
import com.agi.assistant.model.dto.EvaluationTaskRequest;
import com.agi.assistant.model.entity.EvaluationResult;
import com.agi.assistant.model.entity.EvaluationTask;
import com.agi.assistant.model.vo.Result;
import com.agi.assistant.service.EvaluationService;
import com.agi.assistant.service.evaluation.EvaluationSnapshotService;
import com.agi.assistant.service.security.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
    private final EvaluationSnapshotService evaluationSnapshotService;

    /** build 端点未显式传 useLlm 时的默认值（对应 evaluation.benchmark.use-llm）。 */
    @Value("${evaluation.benchmark.use-llm:true}")
    private boolean defaultUseLlm;

    @GetMapping("/datasets")
    @Operation(summary = "可用数据集列表", description = "列出所有可用的评测数据集及其查询数量")
    public Result<List<Map<String, Object>>> listDatasets() {
        return Result.ok(benchmarkDataset.listDatasets());
    }

    @PostMapping("/tasks")
    @Operation(summary = "创建评测任务", description = "创建一个新的RAG评测任务")
    public Result<EvaluationTask> createTask(@Valid @RequestBody EvaluationTaskRequest request) {
        Long userId = UserContext.requireUserId();
        log.info("Create evaluation task for user {}, name {}", userId, request.getName());
        EvaluationTask task = evaluationService.createTask(request, userId);
        return Result.ok(task);
    }

    @GetMapping("/tasks")
    @Operation(summary = "评测任务列表", description = "获取当前用户的评测任务列表")
    public Result<List<EvaluationTask>> listTasks() {
        Long userId = UserContext.requireUserId();
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

    @PostMapping("/datasets/build")
    @Operation(summary = "用 LLM 从文档构建数据集",
            description = "取 COMPLETED/PARTIAL 文档，为每篇生成一条 golden query（逐条落库）；"
                    + "useLlm=false 或 LLM 不可用时 query 回退为文档标题")
    public Result<Map<String, Object>> buildDatasetFromDocuments(
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> req = body == null ? Map.of() : body;
        String datasetId = req.get("datasetId") == null ? null : String.valueOf(req.get("datasetId"));
        int limit = req.get("limit") instanceof Number n ? n.intValue() : 4;
        boolean useLlm = req.get("useLlm") instanceof Boolean b ? b : defaultUseLlm;
        log.info("Build dataset [{}] from documents, limit={}, useLlm={}", datasetId, limit, useLlm);
        int imported = benchmarkDataset.buildFromDocuments(datasetId, limit, useLlm);
        Map<String, Object> result = new HashMap<>();
        result.put("datasetId", datasetId);
        result.put("imported", imported);
        result.put("useLlm", useLlm);
        return Result.ok(result);
    }

    @PostMapping("/tasks/{taskId}/snapshot")
    @Operation(summary = "导出评测结果快照",
            description = "把该任务的全部结果导出为 evaluation.snapshot.dir 下的 json + md（真实数值，未评估为 null）")
    public Result<Map<String, String>> exportSnapshot(
            @Parameter(description = "任务ID") @PathVariable("taskId") Long taskId,
            @Parameter(description = "检索策略（缺省用任务自身策略）")
            @RequestParam(value = "strategy", required = false) String strategy) {
        log.info("Export snapshot for evaluation task {} (strategy={})", taskId, strategy);
        EvaluationSnapshot snapshot = evaluationSnapshotService.exportSnapshot(taskId, strategy);
        return Result.ok(evaluationSnapshotService.snapshotRelativePaths(snapshot));
    }

    @GetMapping("/snapshots")
    @Operation(summary = "已导出快照列表", description = "列出 evaluation.snapshot.dir 下已导出的快照文件（文件名 / 大小 / 时间）")
    public Result<List<Map<String, Object>>> listSnapshots() {
        log.info("List evaluation snapshots");
        return Result.ok(evaluationSnapshotService.listSnapshots());
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
