package com.agi.assistant.controller;

import com.agi.assistant.mapper.AuditLogMapper;
import com.agi.assistant.model.entity.AuditLog;
import com.agi.assistant.model.enums.NodeType;
import com.agi.assistant.model.enums.TaskStatus;
import com.agi.assistant.model.vo.Result;
import com.agi.assistant.service.agent.DAGScheduler;
import com.agi.assistant.service.agent.TaskDAG;
import com.agi.assistant.service.agent.ToolRegistry;
import com.agi.assistant.service.harness.HarnessRuntime;
import com.agi.assistant.service.security.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 能力接口：工具注册表 / DAG 调度 / Harness 状态 / 审计追溯。
 * <p>
 * 为什么需要这个控制器：{@link ToolRegistry}、{@link DAGScheduler}、
 * {@link HarnessRuntime} 这套 Agent 基础设施此前<b>没有任何 HTTP 入口</b>，
 * 只能被聊天主流程内部调用，外部既看不到有哪些工具、也无法触发一次 DAG 编排。
 * 一个能力没有入口，在验收意义上就等同于不存在 —— 这个控制器负责把它接出来。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@Tag(name = "Agent", description = "Agent 编排与工具调用接口")
public class AgentController {

    private static final int MAX_AUDIT_LIMIT = 200;

    private final ToolRegistry toolRegistry;
    private final DAGScheduler dagScheduler;
    private final HarnessRuntime harnessRuntime;
    private final AuditLogMapper auditLogMapper;

    public AgentController(ToolRegistry toolRegistry,
                           DAGScheduler dagScheduler,
                           HarnessRuntime harnessRuntime,
                           @Lazy AuditLogMapper auditLogMapper) {
        this.toolRegistry = toolRegistry;
        this.dagScheduler = dagScheduler;
        this.harnessRuntime = harnessRuntime;
        this.auditLogMapper = auditLogMapper;
    }

    // ----------------------------------------------------------------
    //  工具注册表
    // ----------------------------------------------------------------

    @GetMapping("/tools")
    @Operation(summary = "列出已注册的工具", description = "返回工具名、描述、风险等级与最近执行状态")
    public Result<List<Map<String, Object>>> listTools() {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ToolRegistry.ToolDefinition tool : toolRegistry.listTools()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", tool.getName());
            item.put("description", tool.getDescription());
            item.put("riskLevel", tool.getRiskLevel() != null ? tool.getRiskLevel().name() : null);
            item.put("status", tool.getStatus() != null ? tool.getStatus().name() : null);
            item.put("lastExecutedAt", tool.getLastExecutedAt());
            tools.add(item);
        }
        return Result.ok(tools);
    }

    @PostMapping("/tools/{name}/execute")
    @Operation(summary = "执行工具", description = "按名称调用工具，参数为 JSON 对象；风险分级与审计在注册表内统一处理")
    public Result<Map<String, Object>> executeTool(
            @PathVariable("name") String name,
            @RequestBody(required = false) Map<String, Object> params,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "1") Long userId) {

        if (!toolRegistry.hasTool(name)) {
            return Result.fail(404, "工具不存在: " + name);
        }

        Map<String, Object> result = toolRegistry.executeTool(name, params, userId);
        boolean succeeded = "SUCCESS".equalsIgnoreCase(String.valueOf(result.get("status")));
        return succeeded ? Result.ok(result) : Result.fail("工具执行失败: " + result.get("error"));
    }

    // ----------------------------------------------------------------
    //  DAG 编排
    // ----------------------------------------------------------------

    @PostMapping("/dag/execute")
    @Operation(summary = "执行 DAG", description = "按拓扑序执行任务图，独立节点并行，节点可引用上游产出（${nodeId}）")
    public Result<Map<String, Object>> executeDag(@RequestBody DagRequest request) {
        if (request == null || request.getNodes() == null || request.getNodes().isEmpty()) {
            return Result.fail(400, "nodes 不能为空");
        }

        TaskDAG dag = new TaskDAG();
        try {
            // 1) 建节点
            for (DagNodeSpec spec : request.getNodes()) {
                if (spec.getId() == null || spec.getId().isBlank()) {
                    return Result.fail(400, "节点 id 不能为空");
                }
                NodeType type;
                try {
                    type = NodeType.valueOf(String.valueOf(spec.getType()).trim().toUpperCase());
                } catch (Exception e) {
                    return Result.fail(400, "未知节点类型: " + spec.getType());
                }

                dag.addNode(TaskDAG.TaskNode.builder()
                        .id(spec.getId())
                        .type(type)
                        .config(spec.getConfig() == null ? new LinkedHashMap<>() : spec.getConfig())
                        .build());
            }

            // 2) 建边（addEdge 内部会做自环与成环校验）
            if (request.getEdges() != null) {
                for (DagEdgeSpec edge : request.getEdges()) {
                    if (edge.getSource() == null || edge.getTarget() == null) {
                        return Result.fail(400, "edge 的 source / target 不能为空");
                    }
                    dag.addEdge(edge.getSource(), edge.getTarget());
                }
            }
        } catch (IllegalArgumentException e) {
            // 图结构问题（节点重名、成环、节点不存在）属于请求错误，不是服务端故障
            return Result.fail(400, "DAG 定义非法: " + e.getMessage());
        }

        Map<String, Object> results = dagScheduler.schedule(dag);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("results", results);
        data.put("nodeStatus", nodeStatuses(dag));
        return Result.ok(data);
    }

    private Map<String, Object> nodeStatuses(TaskDAG dag) {
        Map<String, Object> statuses = new LinkedHashMap<>();
        dag.getAllNodes().forEach((id, node) -> statuses.put(id,
                node.getStatus() != null ? node.getStatus().name() : TaskStatus.INITIALIZED.name()));
        return statuses;
    }

    // ----------------------------------------------------------------
    //  Harness 运行状态
    // ----------------------------------------------------------------

    @GetMapping("/harness/status")
    @Operation(summary = "Harness 运行状态", description = "返回各任务状态机当前状态与线程池快照")
    public Result<Map<String, Object>> harnessStatus() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskStatuses", harnessRuntime.getAllStatuses());
        data.put("threadPool", harnessRuntime.poolStats());
        return Result.ok(data);
    }

    // ----------------------------------------------------------------
    //  审计追溯
    // ----------------------------------------------------------------

    @GetMapping("/audit/logs")
    @Operation(summary = "查询审计日志", description = "按时间倒序返回最近的审计记录，可按 userId 过滤")
    public Result<List<AuditLog>> auditLogs(
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {

        int safeLimit = Math.max(1, Math.min(limit, MAX_AUDIT_LIMIT));

        LambdaQueryWrapper<AuditLog> query = new LambdaQueryWrapper<AuditLog>()
                .eq(userId != null, AuditLog::getUserId, userId)
                .orderByDesc(AuditLog::getId)
                .last("LIMIT " + safeLimit);

        return Result.ok(auditLogMapper.selectList(query));
    }

    // ----------------------------------------------------------------
    //  请求体
    // ----------------------------------------------------------------

    @Data
    public static class DagRequest {
        private List<DagNodeSpec> nodes;
        private List<DagEdgeSpec> edges;
    }

    @Data
    public static class DagNodeSpec {
        private String id;
        private String type;
        private Map<String, Object> config;
    }

    @Data
    public static class DagEdgeSpec {
        private String source;
        private String target;
    }
}
