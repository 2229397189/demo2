package com.agi.assistant.service.impl;

import com.agi.assistant.config.OpenAIConfig;
import com.agi.assistant.mapper.ChatMessageMapper;
import com.agi.assistant.mapper.ChatSessionMapper;
import com.agi.assistant.model.dto.ChatRequest;
import com.agi.assistant.model.enums.TaskStatus;
import com.agi.assistant.service.SandboxService;
import com.agi.assistant.service.agent.RaceStrategy;
import com.agi.assistant.service.agent.ReactEngine;
import com.agi.assistant.service.harness.ChatFallbackProvider;
import com.agi.assistant.service.harness.FallbackStrategy;
import com.agi.assistant.service.harness.HarnessRuntime;
import com.agi.assistant.service.harness.RetrievalResultCache;
import com.agi.assistant.service.harness.RetryPolicy;
import com.agi.assistant.service.harness.StateMachine;
import com.agi.assistant.service.harness.TimeoutConfig;
import com.agi.assistant.service.memory.ContextAssembly;
import com.agi.assistant.service.memory.MemoryConsolidation;
import com.agi.assistant.service.memory.ShortTermMemory;
import com.agi.assistant.service.rag.HybridRetrievalService;
import com.agi.assistant.service.rag.WebSearchService;
import com.agi.assistant.service.security.AuditService;
import com.agi.assistant.service.security.InputValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 并发状态机冲突修复 + Harness 降级链接线 的离线验证。
 * <p>
 * <b>完全离线</b>：不启动 Spring 上下文、不连数据库 / 中间件、不调用真实 LLM / 检索，
 * 所有外部协作者均以 stub 替代。
 * <p>
 * 覆盖三组断言：
 * <ol>
 *   <li><b>正向</b>：{@code ChatServiceImpl.taskKey(...)} 生成的请求级唯一 taskName 下，
 *       64 线程并发执行 {@code HarnessRuntime.execute} 不产生任何状态机非法转移；</li>
 *   <li><b>反向对照</b>：故意让所有线程共用一个固定 taskName（复现改造前行为），
 *       并发下 <b>确实</b> 观测到 {@code IllegalStateException} —— 证明本测试真的在测那个 bug；</li>
 *   <li><b>降级路径</b>：检索供应商抛异常时，异常不向上抛、调用方拿到空结果、
 *       且发出降级信号，SSE 流不被打断。</li>
 * </ol>
 */
class ChatConcurrencyTest {

    /** 并发规模 —— 远高于需求下限 50 */
    private static final int CONCURRENCY = 64;

    private static final long EXEC_TIMEOUT_MS = 5_000L;

    private HarnessRuntime harnessRuntime;
    private RecordingConflictStateMachine stateMachine;

    @BeforeEach
    void setUp() {
        stateMachine = new RecordingConflictStateMachine();
        harnessRuntime = newHarnessRuntime(stateMachine, CONCURRENCY);
    }

    @AfterEach
    void tearDown() {
        shutdown(harnessRuntime);
    }

    // ================================================================
    //  ① taskKey 唯一性
    // ================================================================

    @Test
    @DisplayName("taskKey：同一会话的并发请求也生成唯一 key（请求级 UUID 隔离，非仅 sessionId）")
    void taskKeyIsUniquePerRequest() {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            keys.add(ChatServiceImpl.taskKey("rag-retrieval", "sess-1"));
        }
        assertThat(keys)
                .as("同一会话的 1000 次调用必须产生 1000 个不同 key —— 仅 base+sessionId 是不够的")
                .hasSize(1000);

        assertThat(ChatServiceImpl.taskKey("rag-retrieval", "sess-1"))
                .startsWith("rag-retrieval#sess-1#");
        assertThat(ChatServiceImpl.taskKey("rag-retrieval", null))
                .as("sessionId 为 null 时也要能用（UUID 兜底）")
                .startsWith("rag-retrieval#no-session#");
    }

    // ================================================================
    //  ① 正向：唯一 taskName 并发不炸
    // ================================================================

    @Test
    @DisplayName("正向：请求级唯一 taskName 下 64 线程并发无任何线程抛异常，状态机零冲突")
    void uniqueTaskNamesUnderConcurrency_noConflict() {
        ConcurrencyOutcome outcome = runConcurrent(1, CONCURRENCY, true);
        System.out.println("[B2][正向] 请求级唯一 taskName × " + CONCURRENCY
                + " 线程：状态机冲突=" + outcome.conflicts
                + "，冒泡到调用方的异常=" + outcome.thrown.size()
                + "，不同 key 数=" + outcome.distinctKeys);

        assertThat(outcome.thrown)
                .as("唯一 taskName 下不应有任何线程抛异常")
                .isEmpty();
        assertThat(outcome.results)
                .as("全部线程都应正常完成")
                .hasSize(CONCURRENCY)
                .containsOnly("ok");
        assertThat(outcome.conflicts)
                .as("唯一 taskName 下状态机不应发生任何非法转移")
                .isZero();
        assertThat(outcome.distinctKeys)
                .as("64 个并发请求应使用彼此不同的 taskName")
                .isGreaterThan(1);
    }

    // ================================================================
    //  ② 反向对照：证明测试真的在测那个 bug
    // ================================================================

    @Test
    @DisplayName("反向对照：固定 taskName 并发确实触发状态机非法转移（复现改造前行为）")
    void sharedTaskNameUnderConcurrency_reproducesConflict() {
        // 3 轮 × 64 线程，确保并发时序稳定复现，而非偶发
        ConcurrencyOutcome outcome = runConcurrent(3, CONCURRENCY, false);
        System.out.println("[B2][反向] 固定 taskName × " + CONCURRENCY
                + " 线程 × 3 轮：状态机冲突=" + outcome.conflicts
                + "，冒泡到调用方的异常=" + outcome.thrown.size()
                + "，不同 key 数=" + outcome.distinctKeys);

        assertThat(outcome.conflicts)
                .as("共享固定 key 的并发必须观测到 StateMachine 非法转移（改造前的行为）")
                .isGreaterThan(0);

        assertThat(outcome.distinctKeys)
                .as("本组所有线程共用同一个固定 taskName")
                .isEqualTo(1);

        // 关键事实：HarnessRuntime 的 safeTransition 会吞掉状态机异常，
        // 因此该冲突不会冒泡成「调用方可见的异常」。这解释了「用户可见症状与描述不符」。
        assertThat(outcome.thrown)
                .as("safeTransition 吞掉状态机异常，故不应有异常冒泡到调用方")
                .isEmpty();
    }

    // ================================================================
    //  ③ 降级路径：检索抛异常不打断流程
    // ================================================================

    @Test
    @DisplayName("降级：检索供应商抛异常时不打断流程，调用方拿到空结果并收到 retrieval_done")
    void retrievalThrows_degradesWithoutBreakingStream() {
        HybridRetrievalService retrieval = mock(HybridRetrievalService.class);
        when(retrieval.retrieve(anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("retrieval backend down"));

        HarnessRuntime hr = newHarnessRuntime(new StateMachine(), 4);
        try {
            ChatServiceImpl service = buildChatService(hr, retrieval);

            CapturingSseEmitter emitter = new CapturingSseEmitter();
            ChatRequest request = buildRequest();

            assertThatCode(() -> service.streamChat(request, 42L, emitter))
                    .as("检索失败绝不允许把异常抛给调用方（否则 SSE 会被击穿）")
                    .doesNotThrowAnyException();

            assertThat(emitter.error).as("emitter 不应以错误结束").isNull();
            assertThat(emitter.completed).as("流程应正常走到 complete()").isTrue();

            assertThat(emitter.payloadsJoined())
                    .as("应发出 retrieval_done 事件（检索降级对客户端的可见信号）")
                    .contains("retrieval_done");
            assertThat(emitter.payloadsJoined())
                    .as("检索失败后仍继续生成回答（流程未被打断）")
                    .contains("generation");
            assertThat(emitter.payloadsJoined()).doesNotContain("Chat error");
            assertThat(emitter.payloadsJoined()).doesNotContain("Stream error");
            System.out.println("[B2][③a] 检索抛异常：emitter.error=" + emitter.error
                    + "，completed=" + emitter.completed
                    + "，事件=" + emitter.payloadsJoined().replace("\n", " | "));
        } finally {
            shutdown(hr);
        }
    }

    @Test
    @DisplayName("SSE 兜底：harness 意外抛异常时被 try-catch 兜住，发出「检索已降级」且不中断")
    void harnessThrowing_isCaughtByRetrievalGuard() {
        HybridRetrievalService retrieval = mock(HybridRetrievalService.class);

        // HarnessRuntime 中唯一未被 safeTransition 包裹的调用是 stateMachine.reset()，
        // 让其抛异常即模拟「execute 意外向调用方冒出异常」——正是本次 try-catch 要兜住的场景。
        HarnessRuntime hr = newHarnessRuntime(new ThrowingResetStateMachine(), 4);
        try {
            ChatServiceImpl service = buildChatService(hr, retrieval);

            CapturingSseEmitter emitter = new CapturingSseEmitter();
            ChatRequest request = buildRequest();

            assertThatCode(() -> service.streamChat(request, 42L, emitter))
                    .as("harness 冒出的异常必须被 try-catch 兜住，不得击穿 SSE")
                    .doesNotThrowAnyException();

            assertThat(emitter.error).isNull();
            assertThat(emitter.completed).isTrue();
            assertThat(emitter.payloadsJoined())
                    .as("try-catch 兜底后应发出「检索已降级」事件")
                    .contains("检索已降级");
            System.out.println("[B2][③b] harness 抛异常：emitter.error=" + emitter.error
                    + "，completed=" + emitter.completed
                    + "，事件=" + emitter.payloadsJoined().replace("\n", " | "));
        } finally {
            shutdown(hr);
        }
    }

    // ================================================================
    //  测试基础设施
    // ================================================================

    /**
     * 以 {@code rounds} 轮、每轮 {@code threads} 个线程并发执行 {@link HarnessRuntime#execute}。
     *
     * @param rounds     轮数（每轮都开一个全新的线程池，保证真实并发起点）
     * @param threads    每轮线程数
     * @param uniqueKeys true 用 {@link ChatServiceImpl#taskKey} 生成请求级唯一 key；
     *                   false 让所有线程共用固定 key {@code "rag-retrieval"}（复现改造前行为）
     * @return 结果汇总（抛出物 / 返回结果 / 不同 key 数 / 状态机冲突次数）
     */
    private ConcurrencyOutcome runConcurrent(int rounds, int threads, boolean uniqueKeys) {
        int conflictsBefore = stateMachine.conflicts.get();
        List<Throwable> thrown = Collections.synchronizedList(new ArrayList<>());
        List<String> results = Collections.synchronizedList(new ArrayList<>());
        Set<String> keys = Collections.synchronizedSet(new LinkedHashSet<>());

        for (int round = 0; round < rounds; round++) {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch gate = new CountDownLatch(1);

            for (int i = 0; i < threads; i++) {
                final int idx = i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        gate.await();
                        String key = uniqueKeys
                                ? ChatServiceImpl.taskKey("rag-retrieval", "sess-" + (idx % 8))
                                : "rag-retrieval"; // 改造前的固定常量 key
                        keys.add(key);
                        String r = harnessRuntime.execute(() -> {
                            sleepQuietly(2);
                            return "ok";
                        }, key, EXEC_TIMEOUT_MS);
                        results.add(r);
                    } catch (Throwable t) {
                        thrown.add(t);
                    }
                });
            }

            try {
                ready.await();
                gate.countDown();
                pool.shutdown();
                assertThat(pool.awaitTermination(30, TimeUnit.SECONDS))
                        .as("并发线程池应在 30s 内结束").isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while running concurrency scenario", e);
            }
        }

        return new ConcurrencyOutcome(
                new ArrayList<>(thrown),
                new ArrayList<>(results),
                keys.size(),
                stateMachine.conflicts.get() - conflictsBefore);
    }

    /**
     * 构造一个真实的 {@link HarnessRuntime}（含真实线程池、重试、降级、状态机），
     * 关闭重试以避免测试空等。
     */
    private HarnessRuntime newHarnessRuntime(StateMachine sm, int poolSize) {
        TimeoutConfig timeoutConfig = new TimeoutConfig();

        RetryPolicy retryPolicy = new RetryPolicy();
        retryPolicy.setMaxRetries(0);
        retryPolicy.setRetryDelay(0L);
        retryPolicy.setBackoffMultiplier(1.0d);

        FallbackStrategy fallbackStrategy = new FallbackStrategy();

        HarnessRuntime runtime = new HarnessRuntime(timeoutConfig, retryPolicy, fallbackStrategy, sm);

        // @Value 字段在纯单元测试里不会被注入：手动设置后再初始化线程池，
        // 否则 ThreadPoolExecutor 会因 maximumPoolSize <= 0 而构造失败。
        int size = Math.max(2, poolSize);
        ReflectionTestUtils.setField(runtime, "poolCoreSize", size);
        ReflectionTestUtils.setField(runtime, "poolMaxSize", size);
        ReflectionTestUtils.setField(runtime, "poolQueueCapacity", size * 4);
        ReflectionTestUtils.setField(runtime, "poolKeepAliveSeconds", 1L);
        ReflectionTestUtils.invokeMethod(runtime, "initExecutor");
        return runtime;
    }

    /**
     * 用 stub 协作者构造一个 {@link ChatServiceImpl}，仅覆盖「检索降级」相关链路。
     */
    private ChatServiceImpl buildChatService(HarnessRuntime hr, HybridRetrievalService retrieval) {
        ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        when(messageMapper.selectList(any())).thenReturn(new ArrayList<>());

        ContextAssembly contextAssembly = mock(ContextAssembly.class);
        when(contextAssembly.buildMemorySection(any(), anyBoolean(), anyBoolean())).thenReturn("");

        ShortTermMemory shortTermMemory = mock(ShortTermMemory.class);
        MemoryConsolidation consolidation = mock(MemoryConsolidation.class);

        WebSearchService webSearch = mock(WebSearchService.class);
        when(webSearch.search(anyString(), anyInt())).thenReturn(new ArrayList<>());

        SandboxService sandbox = mock(SandboxService.class);
        RaceStrategy race = mock(RaceStrategy.class);
        ReactEngine reactEngine = mock(ReactEngine.class);

        InputValidator inputValidator = mock(InputValidator.class);
        when(inputValidator.validate(anyString())).thenReturn(
                new InputValidator.ValidationResult(
                        true, InputValidator.Severity.CLEAN, List.of(), List.of()));

        AuditService auditService = mock(AuditService.class);

        OpenAIConfig openAIConfig = new OpenAIConfig();
        openAIConfig.setModel("test-model");
        openAIConfig.setTemperature(0.0d);
        openAIConfig.setMaxTokens(32);

        ObjectMapper objectMapper = new ObjectMapper();

        ChatFallbackProvider fallbackProvider = new ChatFallbackProvider(
                new RetrievalResultCache(), shortTermMemory, new FallbackStrategy());

        ChatServiceImpl service = new ChatServiceImpl(
                sessionMapper, messageMapper, retrieval, contextAssembly, shortTermMemory,
                consolidation, openAIConfig, objectMapper, stubStreamingWebClient(),
                webSearch, sandbox, inputValidator, reactEngine, race, hr,
                fallbackProvider, auditService);

        // @Value 字段注入：让 isComplexQuery 只按长度触发（阈值调大 → 短消息不走 ReAct）
        ReflectionTestUtils.setField(service, "reactMinLength", 1000);
        // 注入系统提示词模板资源，避免读 classpath 失败
        ReflectionTestUtils.setField(service, "systemPromptResource",
                new ByteArrayResource("SYS {context} {memory}".getBytes(StandardCharsets.UTF_8)));

        return service;
    }

    private ChatRequest buildRequest() {
        ChatRequest request = new ChatRequest();
        request.setSessionId(1L);
        // 不含任何搜索关键词、长度 < 1000：确保跳过 web-search 与 ReAct 分支
        request.setMessage("你好，请用一句话介绍你自己");
        request.setUseMemory(false);
        request.setRetrievalStrategy("HYBRID");
        return request;
    }

    /**
     * 一个「空的流式响应」WebClient stub：{@code bodyToFlux} 返回空 Flux，
     * 于是 streamChat 走 doOnComplete → 发送 done 事件 → 正常 complete。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private WebClient stubStreamingWebClient() {
        WebClient webClient = mock(WebClient.class);
        WebClient.RequestBodyUriSpec uriSpec = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestBodySpec bodySpec = mock(WebClient.RequestBodySpec.class);
        WebClient.RequestHeadersSpec headersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

        when(webClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
        when(bodySpec.bodyValue(any())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToFlux(DataBuffer.class)).thenReturn(Flux.<DataBuffer>empty());
        return webClient;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 关闭 {@link HarnessRuntime} 的线程池。
     * <p>
     * {@code shutdownExecutor()} 是 harness 包内的 package-private 方法，从 service.impl 包
     * 无法直接访问，故走反射（与 {@code initExecutor} 同一套处理）。
     */
    private static void shutdown(HarnessRuntime runtime) {
        if (runtime != null) {
            ReflectionTestUtils.invokeMethod(runtime, "shutdownExecutor");
        }
    }

    // ================================================================
    //  测试内探针 / 载体
    // ================================================================

    /** 结果汇总载体 */
    private static final class ConcurrencyOutcome {
        final List<Throwable> thrown;
        final List<String> results;
        final int distinctKeys;
        final int conflicts;

        ConcurrencyOutcome(List<Throwable> thrown, List<String> results,
                           int distinctKeys, int conflicts) {
            this.thrown = thrown;
            this.results = results;
            this.distinctKeys = distinctKeys;
            this.conflicts = conflicts;
        }
    }

    /**
     * 记录「状态机非法转移被抛出」的探针。
     * <p>
     * 之所以要记录而不是等异常冒泡：{@link HarnessRuntime} 的 {@code safeTransition}
     * 会把状态机异常吞掉，所以共享 key 的冲突在调用方侧是「看不见」的。
     * 这个探针包装 {@code super.transition}，在异常被吞掉之前先记账，从而能量化冲突。
     */
    static class RecordingConflictStateMachine extends StateMachine {

        final AtomicInteger conflicts = new AtomicInteger();

        @Override
        public TaskStatus transition(String taskName, TaskStatus targetState) {
            try {
                return super.transition(taskName, targetState);
            } catch (IllegalStateException e) {
                conflicts.incrementAndGet();
                throw e;
            }
        }
    }

    /** 让 {@code reset} 抛异常，用于模拟「harness 向调用方意外冒出异常」。 */
    static class ThrowingResetStateMachine extends StateMachine {
        @Override
        public void reset(String taskName) {
            throw new IllegalStateException("simulated harness failure during reset");
        }
    }

    /**
     * 捕获 SSE 事件的 {@link SseEmitter} 探针：记录每条事件的扁平化文本、
     * 是否 complete 过、是否以错误结束。
     */
    static class CapturingSseEmitter extends SseEmitter {

        final List<String> payloads = Collections.synchronizedList(new ArrayList<>());
        volatile Throwable error;
        volatile boolean completed;

        @Override
        public void send(SseEventBuilder builder) {
            StringBuilder sb = new StringBuilder();
            for (DataWithMediaType data : builder.build()) {
                sb.append(data.getData());
            }
            payloads.add(sb.toString());
        }

        @Override
        public void send(Object object) {
            payloads.add(String.valueOf(object));
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public void completeWithError(Throwable ex) {
            error = ex;
        }

        String payloadsJoined() {
            return String.join("\n", payloads);
        }
    }
}
