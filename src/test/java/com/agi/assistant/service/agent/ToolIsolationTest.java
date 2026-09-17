package com.agi.assistant.service.agent;

import com.agi.assistant.model.dto.ToolResult;
import com.agi.assistant.model.enums.ToolRiskLevel;
import com.agi.assistant.model.enums.ToolStatus;
import com.agi.assistant.service.security.AuditService;
import com.agi.assistant.service.security.ToolRiskClassifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 构造性证明「工具维度隔离」：
 * <ul>
 *   <li>一个阻塞的工具（CODE 类）不会拖垮其它类别的工具（SEARCH 类）；</li>
 *   <li>超出超时的工具拿到 {@link ToolStatus#TIMEOUT}（{@code ToolStatus.TIMEOUT} 的真实赋值路径）。</li>
 * </ul>
 * 纯离线：不依赖 Spring 上下文，也不真连任何外部服务。
 */
class ToolIsolationTest {

    private ToolExecutorService executor;
    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        executor = new ToolExecutorService();
        ReflectionTestUtils.setField(executor, "coreSize", 1);
        ReflectionTestUtils.setField(executor, "maxSize", 1);
        ReflectionTestUtils.setField(executor, "queueCapacity", 4);
        ReflectionTestUtils.setField(executor, "keepAliveSeconds", 1L);
        ReflectionTestUtils.setField(executor, "defaultTimeoutMs", 300L);
        executor.initPools();

        AuditService auditService = mock(AuditService.class);
        registry = new ToolRegistry(new ToolRiskClassifier(), auditService, executor);
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownPools();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("单工具阻塞不影响其它类别工具：CODE 阻塞超时，SEARCH 照常立即成功")
    void blockingToolDoesNotStarveOtherCategory() throws Exception {
        CountDownLatch slowToolStarted = new CountDownLatch(1);

        // CODE 类：一进 handler 就睡 2 秒，远超 300ms 超时
        registry.registerTool("run_code", "slow code tool", ToolRiskLevel.WARN,
                (ToolHandler) params -> {
                    slowToolStarted.countDown();
                    sleepQuietly(2000);
                    return ToolResult.success("run_code", "late", null);
                });
        // SEARCH 类：立即返回
        registry.registerTool("web_search", "fast search tool", ToolRiskLevel.SAFE,
                (ToolHandler) params -> ToolResult.success("web_search", "quick", null));

        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<ToolResult> slowFuture =
                    callers.submit(() -> registry.executeToolTyped("run_code", Map.of("code", "x"), 1L));

            // 确保阻塞工具已经真正占据 CODE 池线程
            assertThat(slowToolStarted.await(1, TimeUnit.SECONDS))
                    .as("阻塞工具应已开始执行").isTrue();

            // 此时 CODE 池被独占，但 SEARCH 是独立池，必须立即返回
            long start = System.currentTimeMillis();
            ToolResult fast = registry.executeToolTyped("web_search", Map.of("query", "q"), 1L);
            long fastElapsed = System.currentTimeMillis() - start;

            assertThat(fast.getStatus()).isEqualTo(ToolStatus.SUCCESS);
            assertThat(fast.getContent()).isEqualTo("quick");
            assertThat(fastElapsed)
                    .as("SEARCH 不能被阻塞的 CODE 工具拖慢（阻塞工具要睡 2000ms）")
                    .isLessThan(1000L);

            ToolResult slow = slowFuture.get(3, TimeUnit.SECONDS);
            assertThat(slow.getStatus())
                    .as("阻塞工具应因超时拿到 TIMEOUT 状态")
                    .isEqualTo(ToolStatus.TIMEOUT);
            assertThat(slow.getElapsedMs()).isGreaterThanOrEqualTo(250L);
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    @DisplayName("executeIsolated 对超时动作返回 TIMEOUT 并取消 future（TIMEOUT 有真实赋值路径）")
    void executeIsolatedReturnsTimeoutAndCancelsFuture() {
        ToolResult result = executor.executeIsolated(
                "run_code",
                () -> {
                    sleepQuietly(2000);
                    return ToolResult.success("run_code", "never", null);
                },
                200L);

        assertThat(result.getStatus()).isEqualTo(ToolStatus.TIMEOUT);
        assertThat(result.getElapsedMs()).isGreaterThanOrEqualTo(150L);
        assertThat(result.getError()).contains("timed out");
    }

    @Test
    @DisplayName("类别映射：已知工具归入对应池，未知工具归 DEFAULT")
    void resolvesToolCategories() {
        assertThat(executor.resolveCategory("knowledge_search")).isEqualTo(ToolExecutorService.ToolCategory.SEARCH);
        assertThat(executor.resolveCategory("web_search")).isEqualTo(ToolExecutorService.ToolCategory.SEARCH);
        assertThat(executor.resolveCategory("memory_search")).isEqualTo(ToolExecutorService.ToolCategory.MEMORY);
        assertThat(executor.resolveCategory("calculate")).isEqualTo(ToolExecutorService.ToolCategory.COMPUTE);
        assertThat(executor.resolveCategory("run_code")).isEqualTo(ToolExecutorService.ToolCategory.CODE);
        assertThat(executor.resolveCategory("something_unknown")).isEqualTo(ToolExecutorService.ToolCategory.DEFAULT);
    }
}
