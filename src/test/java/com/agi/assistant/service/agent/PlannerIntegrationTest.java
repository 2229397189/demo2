package com.agi.assistant.service.agent;

import com.agi.assistant.config.OpenAIConfig;
import com.agi.assistant.model.dto.ToolResult;
import com.agi.assistant.model.enums.ToolRiskLevel;
import com.agi.assistant.model.enums.ToolStatus;
import com.agi.assistant.service.memory.ContextAssembly;
import com.agi.assistant.service.memory.PlanFactory;
import com.agi.assistant.service.memory.RuntimeStateMemory;
import com.agi.assistant.service.security.ToolRiskClassifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Runtime Planner 状态接通 + 工具结果 PARTIAL 标记的集成级离线单测。
 * <p>
 * 覆盖六组验证：
 * <ol>
 *   <li>Planner 写入生效（计划非空、逐轮推进、结束标记完成）；</li>
 *   <li>Planner 段真实进入运行态上下文（ContextAssembly 渲染非空 Planner 段）；</li>
 *   <li>{@code sessionId == null} 不炸、不写脏数据；</li>
 *   <li>工具结果被截断时状态可达 {@link ToolStatus#PARTIAL} 且 {@code truncated == true}；</li>
 *   <li>埋点（RuntimeStateMemory 写方法）抛异常不影响推理主流程；</li>
 *   <li>{@link PlanFactory} 回退路径返回非空的有意义步骤列表。</li>
 * </ol>
 * 全程离线：不启动 Spring 上下文、不连真实 LLM / 中间件；LLM 通过覆写 {@code think} 的
 * 桩引擎替身，工具通过 {@link ToolRegistry} 注册的本地 handler。
 */
class PlannerIntegrationTest {

    private ToolExecutorService executor;
    private ToolRegistry toolRegistry;
    private RuntimeStateMemory memory;

    @BeforeEach
    void setUp() {
        executor = new ToolExecutorService();
        ReflectionTestUtils.setField(executor, "coreSize", 2);
        ReflectionTestUtils.setField(executor, "maxSize", 4);
        ReflectionTestUtils.setField(executor, "queueCapacity", 8);
        ReflectionTestUtils.setField(executor, "keepAliveSeconds", 1L);
        ReflectionTestUtils.setField(executor, "defaultTimeoutMs", 2000L);
        executor.initPools();

        // auditService 传 null：ToolRegistry.audit 对 null 直接返回，无需 Mockito、无副作用
        toolRegistry = new ToolRegistry(new ToolRiskClassifier(), null, executor);
        memory = new RuntimeStateMemory();
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownPools();
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  测试替身
    // ──────────────────────────────────────────────────────────────

    /**
     * 用队列预置「思考文本」的 ReactEngine 替身：覆写 {@code think} 直接吐预置的
     * Thought/Action 文本，从而绕开真实 LLM（{@code WebClient} 传 null）。
     */
    private static final class StubReactEngine extends ReactEngine {

        private final Deque<String> thoughts = new ArrayDeque<>();

        StubReactEngine(ToolRegistry toolRegistry, RuntimeStateMemory memory) {
            super(null, toolRegistry, null, new OpenAIConfig(),
                    memory, new PlanFactory(null, new OpenAIConfig()));
        }

        StubReactEngine enqueue(String... thoughtTexts) {
            for (String text : thoughtTexts) {
                thoughts.add(text);
            }
            return this;
        }

        @Override
        public String think(String query, String context) {
            return thoughts.isEmpty() ? null : thoughts.poll();
        }
    }

    private StubReactEngine newEngine() {
        return new StubReactEngine(toolRegistry, memory);
    }

    private void registerEchoTool(String content) {
        toolRegistry.registerTool("echo", "回显工具", ToolRiskLevel.SAFE,
                (ToolHandler) params -> ToolResult.success("echo", content, null));
    }

    // ──────────────────────────────────────────────────────────────
    //  验证 1：Planner 写入生效
    // ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("验证1 - Planner 写入生效：计划非空、逐轮推进、结束标记已完成")
    void plannerWriteEffective() {
        registerEchoTool("ok");
        String sessionId = "sess-plan-1";
        StubReactEngine engine = newEngine();
        engine.enqueue(
                "Thought: 先调用工具\nAction: echo(hi)",
                "Thought: 已获得结果\nAction: finish(done)");

        String answer = engine.run("请回答问题", 5, 1L, sessionId);

        assertThat(answer).isEqualTo("done");

        RuntimeStateMemory.PlannerState planner = memory.getOrCreatePlannerState(sessionId);
        assertThat(planner.getPlan())
                .as("计划必须非空（改造前恒为 null/空）")
                .isNotNull()
                .isNotEmpty();
        assertThat(planner.getPlan()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(planner.getCurrentStepIndex())
                .as("advancePlanStep 必须被调用过（下标至少推进到 1）")
                .isGreaterThanOrEqualTo(1);
        assertThat(planner.getStatus())
                .as("推理结束后计划状态应为已完成")
                .isEqualTo(RuntimeStateMemory.PlanStatus.COMPLETED);
    }

    // ──────────────────────────────────────────────────────────────
    //  验证 2：Planner 段真实进入上下文
    // ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("验证2 - Planner 段进入上下文：ContextAssembly 渲染出非空 Planner 段")
    void plannerSectionRenderedInContext() {
        registerEchoTool("ok");
        String sessionId = "sess-ctx-2";
        StubReactEngine engine = newEngine();
        engine.enqueue(
                "Thought: 调用工具\nAction: echo(hi)",
                "Thought: 收敛\nAction: finish(done)");

        // 改造前后对比：跑推理之前 Planner 段为空
        String before = memory.assembleRuntimeContext(sessionId);
        assertThat(before)
                .as("推理前 Planner 段应为空（对照基线）")
                .doesNotContain("当前计划状态");

        engine.run("请回答问题", 5, 1L, sessionId);

        // 直接读运行态渲染
        String after = memory.assembleRuntimeContext(sessionId);
        assertThat(after).contains("当前计划状态");

        // 通过 ContextAssembly 组装的 Runtime Context 同样包含非空 Planner 段
        ContextAssembly assembly = new ContextAssembly(null, null, null, null, memory);
        Map<String, Object> ctx = assembly.assembleContext(1L, sessionId, "task-2");
        Object runtimeState = ctx.get("runtimeState");
        assertThat(runtimeState).isInstanceOf(String.class);

        String runtimeContext = (String) runtimeState;
        assertThat(runtimeContext).contains("当前计划状态");

        String firstStep = memory.getOrCreatePlannerState(sessionId).getPlan().get(0);
        assertThat(runtimeContext)
                .as("Planner 段必须含真实计划步骤文本，而非空壳标题")
                .contains(firstStep);
    }

    // ──────────────────────────────────────────────────────────────
    //  验证 3：sessionId == null 不炸、不写脏数据
    // ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("验证3 - sessionId==null：不抛异常且不写脏数据")
    @SuppressWarnings("unchecked")
    void nullSessionIdIsSafe() {
        registerEchoTool("ok");
        StubReactEngine engine = newEngine();
        engine.enqueue(
                "Thought: 调用工具\nAction: echo(hi)",
                "Thought: 收敛\nAction: finish(done)");

        String answer = assertDoesNotThrow(() -> engine.run("问题", 5, 1L, null));
        assertThat(answer).isEqualTo("done");

        assertThat((Map<?, ?>) ReflectionTestUtils.getField(memory, "plannerStates"))
                .as("null 会话不应写入 Planner State")
                .isEmpty();
        assertThat((Map<?, ?>) ReflectionTestUtils.getField(memory, "toolStates"))
                .as("null 会话不应写入 Tool State")
                .isEmpty();
        assertThat((Map<?, ?>) ReflectionTestUtils.getField(memory, "taskMemories"))
                .as("null 会话不应写入 Task Memory")
                .isEmpty();
    }

    // ──────────────────────────────────────────────────────────────
    //  验证 4：PARTIAL 可达
    // ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("验证4 - PARTIAL 可达：超长工具结果被截断 → 状态 PARTIAL 且 truncated==true")
    void partialReachableOnTruncation() {
        String longContent = "长".repeat(5000);
        toolRegistry.registerTool("big_tool", "返回超长内容的工具", ToolRiskLevel.SAFE,
                (ToolHandler) params -> ToolResult.success("big_tool", longContent, null));

        String sessionId = "sess-partial-4";
        StubReactEngine engine = newEngine();
        engine.enqueue(
                "Thought: 调用长结果工具\nAction: big_tool(x)",
                "Thought: 收敛\nAction: finish(done)");

        engine.run("问题", 5, 1L, sessionId);

        ToolResult last = engine.getLastToolResult();
        assertThat(last).as("最近一次工具结果不应为 null").isNotNull();
        assertThat(last.getStatus())
                .as("超长结果被截断后应标记为 PARTIAL（改造前该状态零赋值路径）")
                .isEqualTo(ToolStatus.PARTIAL);
        assertThat(last.isTruncated()).isTrue();
        assertThat(last.getPartialReason())
                .as("截断原因需写明原始长度")
                .contains("截断")
                .contains("5000");
    }

    // ──────────────────────────────────────────────────────────────
    //  验证 5：埋点失败不影响主流程
    // ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("验证5 - 埋点失败不影响主流程：写方法抛异常，推理仍正常完成")
    void instrumentationFailureDoesNotBreakInference() {
        registerEchoTool("ok");

        RuntimeStateMemory throwingMemory = new RuntimeStateMemory() {
            @Override
            public void updatePlan(String sessionId, List<String> steps) {
                throw new IllegalStateException("boom-updatePlan");
            }

            @Override
            public boolean advancePlanStep(String sessionId) {
                throw new IllegalStateException("boom-advancePlanStep");
            }

            @Override
            public void completePlan(String sessionId) {
                throw new IllegalStateException("boom-completePlan");
            }
        };

        StubReactEngine engine = new StubReactEngine(toolRegistry, throwingMemory);
        engine.enqueue(
                "Thought: 调用工具\nAction: echo(hi)",
                "Thought: 收敛\nAction: finish(done)");

        String answer = assertDoesNotThrow(() -> engine.run("问题", 5, 1L, "sess-fail-5"));
        assertThat(answer)
                .as("Planner 埋点抛异常被 try-catch 吞掉，推理仍应返回正确答案")
                .isEqualTo("done");
    }

    // ──────────────────────────────────────────────────────────────
    //  验证 6：PlanFactory 回退路径
    // ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("验证6 - PlanFactory 回退路径：useLlm=false 返回非空有意义步骤")
    void planFactoryFallbackMeaningful() {
        PlanFactory factory = new PlanFactory(null, new OpenAIConfig());

        List<String> steps = factory.build("请分析这个系统的架构问题", false);

        assertThat(steps).isNotNull();
        assertThat(steps).as("回退计划至少 3 步").hasSizeGreaterThanOrEqualTo(3);
        for (String step : steps) {
            assertThat(step).as("每个步骤都必须是非空字符串").isNotBlank();
        }
        assertThat(steps)
                .as("不得返回 step1/step2 之类的占位符")
                .doesNotContain("step1", "step2");

        // useLlm=true 但 LLM 不可用（无 apiKey）→ 仍回退，绝不返回空
        List<String> llmRequested = factory.build("请分析这个系统的架构问题", true);
        assertThat(llmRequested).isNotEmpty();
    }
}
