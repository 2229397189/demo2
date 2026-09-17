package com.agi.assistant.service.agent;

import com.agi.assistant.config.OpenAIConfig;
import com.agi.assistant.model.entity.SearchResult;
import com.agi.assistant.model.enums.NodeType;
import com.agi.assistant.model.enums.TaskStatus;
import com.agi.assistant.service.rag.HybridRetrievalService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link DAGScheduler} 测试。
 * <p>
 * 两个必须守住的回归点：
 * <ol>
 *   <li><b>依赖结果可见性</b>：修复前节点执行体读的 {@code results} 只在全部跑完后才回填，
 *       于是 {@code depResults} 恒为空，上游产出永远传不到下游 ——
 *       编排「看起来能跑」，实际每个节点都是孤岛。</li>
 *   <li><b>节点处理器是真的</b>：修复前五个处理器统一返回
 *       {@code status:"prepared"}，既不调模型也不检索也不调工具。</li>
 * </ol>
 * <p>
 * 注意：{@code executeNode} 的 {@code depResults} 是按<b>DAG 里的边</b>装配的，
 * 所以凡是涉及「读到上游产出」的用例，都必须真的连边（见 {@link #dagWithUpstreams}）。
 */
class DAGSchedulerTest {

    private ExecutorService executor;
    private HybridRetrievalService hybridRetrievalService;
    private ToolRegistry toolRegistry;
    private DAGScheduler scheduler;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);
        hybridRetrievalService = mock(HybridRetrievalService.class);
        toolRegistry = mock(ToolRegistry.class);
        scheduler = new DAGScheduler(
                executor,
                mock(WebClient.class),   // LLM 真实调用需要联网，不在单元测试范围
                new OpenAIConfig(),
                hybridRetrievalService,
                toolRegistry);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    // ──────────────────────────────────────────────────────────────
    //  辅助
    // ──────────────────────────────────────────────────────────────

    private static Map<String, Object> cfg(Object... kv) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    private static TaskDAG.TaskNode node(String id, NodeType type, Map<String, Object> config) {
        return TaskDAG.TaskNode.builder().id(id).type(type).config(config).build();
    }

    /**
     * 建一个「若干上游节点 → target」的 DAG。
     * 上游占位节点不会被本用例执行，只用来提供依赖边。
     */
    private static TaskDAG dagWithUpstreams(String targetId, NodeType targetType,
                                            Map<String, Object> config, String... upstreamIds) {
        TaskDAG dag = new TaskDAG();
        for (String id : upstreamIds) {
            dag.addNode(node(id, NodeType.MERGE, cfg()));
        }
        dag.addNode(node(targetId, targetType, config));
        for (String id : upstreamIds) {
            dag.addEdge(id, targetId);
        }
        return dag;
    }

    private static Map<String, Object> upstreamOf(String key, Object value) {
        Map<String, Object> map = new HashMap<>();
        map.put(key, value);
        return map;
    }

    /** 节点结果统一按 Map<String,Object> 断言（避免 Map<?,?> 的通配捕获让 containsEntry 无法使用字面量） */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static boolean evaluated(Object conditionResult) {
        return (Boolean) asMap(conditionResult).get("evaluated");
    }

    // ──────────────────────────────────────────────────────────────
    //  回归 1：依赖结果必须传到下游
    // ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("下游节点能读到上游产出（修复前 depResults 恒为空）")
    void downstreamNodeSeesUpstreamResult() {
        when(toolRegistry.executeTool(eq("calculate"), anyMap(), any()))
                .thenReturn(Map.<String, Object>of("status", "success", "result", "42.0"));

        TaskDAG dag = new TaskDAG();
        dag.addNode(node("a", NodeType.TOOL_CALL, cfg(
                "tool", "calculate",
                "params", new HashMap<>(Map.of("expression", "6*7")))));
        dag.addNode(node("b", NodeType.MERGE, cfg("mergeStrategy", "concat")));
        dag.addEdge("a", "b");

        Map<String, Object> results = scheduler.schedule(dag);

        assertThat(results).containsKeys("a", "b");
        assertThat(results.get("b")).isInstanceOf(Map.class);
        String mergedText = String.valueOf(((Map<?, ?>) results.get("b")).get("text"));
        assertThat(mergedText).as("下游必须拿到上游产出").isEqualTo("42.0");

        assertThat(dag.getNode("a").getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(dag.getNode("b").getStatus()).isEqualTo(TaskStatus.COMPLETED);
    }

    @Test
    @DisplayName("节点失败时也把失败结果发布出去，供下游判断而非静默读 null")
    void failureIsPublishedForDownstream() {
        TaskDAG dag = new TaskDAG();
        dag.addNode(node("bad", NodeType.TOOL_CALL, cfg()));   // 缺 tool → 必然失败
        dag.addNode(node("cond", NodeType.CONDITION, cfg("ref", "bad", "op", "nonEmpty")));
        dag.addEdge("bad", "cond");

        Map<String, Object> results = scheduler.schedule(dag);

        assertThat(results.get("bad")).isInstanceOf(Map.class);
        assertThat(asMap(results.get("bad"))).containsEntry("status", "failed");
        // 下游看到的是「上游失败」，因此条件判 false —— 而不是拿到 null 后含糊通过
        assertThat(evaluated(results.get("cond"))).isFalse();
        assertThat(dag.getNode("bad").getStatus()).isEqualTo(TaskStatus.FAILED);
    }

    @Test
    @DisplayName("空 DAG 返回空结果，不抛异常")
    void emptyDagReturnsEmptyMap() {
        assertThat(scheduler.schedule(new TaskDAG())).isEmpty();
        assertThat(scheduler.schedule(null)).isEmpty();
    }

    @Test
    @DisplayName("未知节点返回 null")
    void unknownNodeReturnsNull() {
        assertThat(scheduler.executeNode(new TaskDAG(), "ghost", new HashMap<>())).isNull();
    }

    // ──────────────────────────────────────────────────────────────
    //  回归 2：TOOL_CALL 真走注册表 + 占位符解析
    // ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("TOOL_CALL 把 ${nodeId} 替换成上游产出后再调工具")
    void toolCallResolvesPlaceholderFromUpstream() {
        when(toolRegistry.executeTool(eq("knowledge_search"), anyMap(), eq(7L)))
                .thenReturn(Map.<String, Object>of("status", "success", "result", "检索结果"));

        TaskDAG dag = dagWithUpstreams("t", NodeType.TOOL_CALL, cfg(
                "tool", "knowledge_search",
                "params", new HashMap<>(Map.of("query", "${up}")),
                "userId", 7L), "up");

        Object out = scheduler.executeNode(dag, "t", upstreamOf("up", "上一步的结论"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(toolRegistry).executeTool(eq("knowledge_search"), captor.capture(), eq(7L));

        assertThat(captor.getValue().get("query"))
                .as("占位符必须被替换成上游产出，而不是原样透传")
                .isEqualTo("上一步的结论");
        assertThat(asMap(out)).containsEntry("status", "success");
        assertThat(dag.getNode("t").getStatus()).isEqualTo(TaskStatus.COMPLETED);
    }

    @Test
    @DisplayName("TOOL_CALL 缺少 tool 配置 → 节点 FAILED 且不调工具")
    void toolCallWithoutToolNameFails() {
        TaskDAG dag = new TaskDAG();
        dag.addNode(node("t", NodeType.TOOL_CALL, cfg()));

        Object out = scheduler.executeNode(dag, "t", new HashMap<>());

        assertThat(asMap(out)).containsEntry("status", "failed");
        assertThat(String.valueOf(asMap(out).get("error"))).contains("tool");
        assertThat(dag.getNode("t").getStatus()).isEqualTo(TaskStatus.FAILED);
        verifyNoInteractions(toolRegistry);
    }

    // ──────────────────────────────────────────────────────────────
    //  RAG_RETRIEVE
    // ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("RAG_RETRIEVE 真检索并产出可读预览")
    void ragRetrieveReturnsRealChunks() {
        when(hybridRetrievalService.retrieve(eq("什么是 RAG"), eq("HYBRID"), eq(5)))
                .thenReturn(List.of(SearchResult.builder()
                        .title("T").content("RAG 是检索增强生成").source("doc").score(0.8).build()));

        TaskDAG dag = new TaskDAG();
        dag.addNode(node("r", NodeType.RAG_RETRIEVE, cfg("query", "什么是 RAG")));

        Object out = scheduler.executeNode(dag, "r", new HashMap<>());
        Map<String, Object> result = asMap(out);

        assertThat(result).containsEntry("count", 1);
        assertThat(result).containsEntry("strategy", "HYBRID");
        assertThat(result).containsEntry("status", "success");
        assertThat(String.valueOf(result.get("preview"))).contains("RAG 是检索增强生成");
    }

    @Test
    @DisplayName("RAG_RETRIEVE 的 query 支持 ${nodeId} 引用上游")
    void ragRetrieveResolvesUpstreamReference() {
        when(hybridRetrievalService.retrieve(eq("上一步的结论"), anyString(), anyInt()))
                .thenReturn(List.of());

        TaskDAG dag = dagWithUpstreams("r", NodeType.RAG_RETRIEVE, cfg("query", "${up}"), "up");

        Object out = scheduler.executeNode(dag, "r", upstreamOf("up", "上一步的结论"));

        assertThat(asMap(out)).containsEntry("query", "上一步的结论");
    }

    @Test
    @DisplayName("RAG_RETRIEVE 引用解析为空 → 节点 FAILED，不拿空串去检索")
    void ragRetrieveWithBlankQueryFails() {
        TaskDAG dag = new TaskDAG();
        dag.addNode(node("r", NodeType.RAG_RETRIEVE, cfg("query", "${ghost}")));

        Object out = scheduler.executeNode(dag, "r", new HashMap<>());

        assertThat(asMap(out)).containsEntry("status", "failed");
        assertThat(dag.getNode("r").getStatus()).isEqualTo(TaskStatus.FAILED);
        verifyNoInteractions(hybridRetrievalService);
    }

    // ──────────────────────────────────────────────────────────────
    //  CONDITION
    // ──────────────────────────────────────────────────────────────

    private boolean runCondition(Map<String, Object> config, Object upstreamValue) {
        TaskDAG dag = dagWithUpstreams("c", NodeType.CONDITION, config, "a");
        return evaluated(scheduler.executeNode(dag, "c", upstreamOf("a", upstreamValue)));
    }

    @Test
    @DisplayName("nonEmpty：有内容为 true，空/失败为 false")
    void conditionNonEmpty() {
        assertThat(runCondition(cfg("ref", "a", "op", "nonEmpty"), "有内容")).isTrue();
        assertThat(runCondition(cfg("ref", "a", "op", "nonEmpty"), "")).isFalse();
        assertThat(runCondition(cfg("ref", "a", "op", "nonEmpty"), null)).isFalse();
        // 检索节点返回 count=0 视为「无可用产出」
        assertThat(runCondition(cfg("ref", "a", "op", "nonEmpty"), Map.of("count", 0))).isFalse();
        assertThat(runCondition(cfg("ref", "a", "op", "nonEmpty"), Map.of("count", 3))).isTrue();
        // 上游失败的节点，不该被当成「有内容」
        assertThat(runCondition(cfg("ref", "a", "op", "nonEmpty"),
                Map.of("status", "failed", "error", "boom"))).isFalse();
    }

    @Test
    @DisplayName("op 缺省为 nonEmpty")
    void conditionDefaultsToNonEmpty() {
        assertThat(runCondition(cfg("ref", "a"), "有内容")).isTrue();
        assertThat(runCondition(cfg("ref", "a"), "")).isFalse();
    }

    @Test
    @DisplayName("empty / exists / notExists")
    void conditionEmptyAndExistence() {
        assertThat(runCondition(cfg("ref", "a", "op", "empty"), Map.of("count", 0))).isTrue();
        assertThat(runCondition(cfg("ref", "a", "op", "empty"), "有内容")).isFalse();

        assertThat(runCondition(cfg("ref", "a", "op", "exists"), "x")).isTrue();
        assertThat(runCondition(cfg("ref", "a", "op", "exists"), null)).isFalse();

        assertThat(runCondition(cfg("ref", "a", "op", "notExists"), null)).isTrue();
        assertThat(runCondition(cfg("ref", "a", "op", "notExists"), "x")).isFalse();
    }

    @Test
    @DisplayName("contains / equals 从上游产物里抽文本比较")
    void conditionContainsAndEquals() {
        assertThat(runCondition(cfg("ref", "a", "op", "contains", "value", "世界"),
                Map.of("answer", "你好世界"))).isTrue();
        assertThat(runCondition(cfg("ref", "a", "op", "contains", "value", "月球"),
                Map.of("answer", "你好世界"))).isFalse();

        assertThat(runCondition(cfg("ref", "a", "op", "equals", "value", "42"),
                Map.of("text", "42"))).isTrue();
        assertThat(runCondition(cfg("ref", "a", "op", "equals", "value", "7"),
                Map.of("text", "42"))).isFalse();
    }

    @Test
    @DisplayName("gt 数值比较，非数值安全判 false")
    void conditionGreaterThan() {
        assertThat(runCondition(cfg("ref", "a", "op", "gt", "value", "10"),
                Map.of("answer", "42"))).isTrue();
        assertThat(runCondition(cfg("ref", "a", "op", "gt", "value", "100"),
                Map.of("answer", "42"))).isFalse();
        // 非数值：不能抛异常把节点打挂，应判 false
        assertThat(runCondition(cfg("ref", "a", "op", "gt", "value", "10"), "不是数字")).isFalse();
        // 缺少比较值
        assertThat(runCondition(cfg("ref", "a", "op", "gt"), "42")).isFalse();
    }

    @Test
    @DisplayName("未知 op 回落到 nonEmpty，不抛异常")
    void conditionUnknownOpFallsBack() {
        assertThat(runCondition(cfg("ref", "a", "op", "telepathy"), "有内容")).isTrue();
        assertThat(runCondition(cfg("ref", "a", "op", "telepathy"), "")).isFalse();
    }

    // ──────────────────────────────────────────────────────────────
    //  MERGE
    // ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("MERGE concat 按分隔符拼接所有上游产出")
    void mergeConcatJoinsParts() {
        TaskDAG dag = dagWithUpstreams("m", NodeType.MERGE,
                cfg("mergeStrategy", "concat", "separator", " | "), "a", "b");

        Map<String, Object> upstream = new LinkedHashMap<>();
        upstream.put("a", "第一段");
        upstream.put("b", "第二段");

        Object out = scheduler.executeNode(dag, "m", upstream);
        Map<String, Object> result = asMap(out);

        assertThat(result).containsEntry("parts", 2);
        String text = String.valueOf(result.get("text"));
        assertThat(text).contains("第一段").contains("第二段").contains(" | ");
    }

    @Test
    @DisplayName("MERGE first 只取第一个上游产出")
    void mergeFirstTakesOne() {
        TaskDAG dag = dagWithUpstreams("m", NodeType.MERGE, cfg("mergeStrategy", "first"), "a");

        Object out = scheduler.executeNode(dag, "m", upstreamOf("a", "唯一一段"));

        assertThat(asMap(out)).containsEntry("text", "唯一一段");
    }

    @Test
    @DisplayName("MERGE json 产出结构化结果")
    void mergeJsonProducesStructuredOutput() {
        TaskDAG dag = dagWithUpstreams("m", NodeType.MERGE, cfg("mergeStrategy", "json"), "a");

        Object out = scheduler.executeNode(dag, "m", upstreamOf("a", "内容"));
        Map<String, Object> result = asMap(out);

        assertThat(result).containsEntry("type", "merge");
        assertThat(result).containsEntry("strategy", "json");
        assertThat(result.get("inputs")).isInstanceOf(Map.class);
        assertThat(String.valueOf(result.get("text"))).contains("内容");
    }

    @Test
    @DisplayName("MERGE 忽略空产出，未知策略回落 concat")
    void mergeIgnoresBlankAndUnknownStrategy() {
        TaskDAG dag = dagWithUpstreams("m", NodeType.MERGE,
                cfg("mergeStrategy", "concat", "separator", ","), "a", "b", "c");

        Map<String, Object> upstream = new LinkedHashMap<>();
        upstream.put("a", "有效");
        upstream.put("b", "");
        upstream.put("c", null);

        Object out = scheduler.executeNode(dag, "m", upstream);

        assertThat(asMap(out)).containsEntry("parts", 1);
        assertThat(asMap(out)).containsEntry("text", "有效");

        TaskDAG unknown = dagWithUpstreams("m", NodeType.MERGE, cfg("mergeStrategy", "quantum"), "a");
        Object fallback = scheduler.executeNode(unknown, "m", upstreamOf("a", "x"));
        assertThat(asMap(fallback)).containsEntry("strategy", "quantum");
        assertThat((asMap(fallback)).get("text")).isEqualTo("x");
    }

    // ──────────────────────────────────────────────────────────────
    //  LLM_CALL
    // ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LLM_CALL 缺少 prompt → 节点 FAILED，错误信息指明原因")
    void llmCallWithoutPromptFails() {
        TaskDAG dag = new TaskDAG();
        dag.addNode(node("l", NodeType.LLM_CALL, cfg("model", "glm-4.5-air")));

        Object out = scheduler.executeNode(dag, "l", new HashMap<>());

        assertThat(asMap(out)).containsEntry("status", "failed");
        assertThat(String.valueOf((asMap(out)).get("error"))).contains("prompt");
        assertThat(dag.getNode("l").getStatus()).isEqualTo(TaskStatus.FAILED);
    }

    @ParameterizedTest
    @CsvSource({"LLM_CALL", "TOOL_CALL", "RAG_RETRIEVE", "CONDITION", "MERGE"})
    @DisplayName("五种节点类型都有真实处理器，且都会落到终态")
    void allNodeTypesAreHandled(String typeName) {
        NodeType type = NodeType.valueOf(typeName);

        TaskDAG dag = new TaskDAG();
        dag.addNode(node("n", type, cfg()));

        Object out = scheduler.executeNode(dag, "n", new HashMap<>());

        // 无论成功还是失败，都必须返回一个结果对象；不存在「静默返回 null 当作没这回事」
        assertThat(out).as("节点 %s 必须产出结果", typeName).isNotNull();
        assertThat(dag.getNode("n").getStatus()).as("节点 %s 必须有终态", typeName)
                .isIn(TaskStatus.COMPLETED, TaskStatus.FAILED);
    }
}
