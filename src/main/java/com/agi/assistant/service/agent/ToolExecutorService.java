package com.agi.assistant.service.agent;

import com.agi.assistant.model.dto.ToolResult;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 工具隔离执行器。
 * <p>
 * 修复说明：此前所有工具共用同一个调用线程（由 ReAct/HTTP 请求线程直接执行），
 * 一个阻塞的工具（如沙箱执行、慢检索）会把调用线程整个卡住，其它工具连「排队」
 * 的机会都没有 —— 「工具维度隔离」形同虚设。
 * <p>
 * 本类把工具按<b>类别</b>（{@link ToolCategory}）划分到<b>各自独立的有界线程池</b>：
 * <ul>
 *   <li>慢工具（CODE：沙箱执行）拖慢自身池，不影响 SEARCH / MEMORY / COMPUTE；</li>
 *   <li>每个池都是 {@link ThreadPoolExecutor} + {@link ArrayBlockingQueue} + 明确拒绝策略，
 *       <b>绝不无界</b> —— 过载时快速失败（返回 {@code FAILURE}），而不是把线程堆到 OOM；</li>
 *   <li>{@link #executeIsolated(String, Supplier, long)} 用 {@code future.get(timeout)}
 *       施加上限，超时即 {@code cancel(true)} 并返回 {@link ToolResult#timeout}，
 *       这是 {@code ToolStatus.TIMEOUT} 在生产环境的真实赋值路径。</li>
 * </ul>
 * 池参数与默认超时复用 {@code HarnessRuntime} 的既有配置键（{@code harness.pool.*} /
 * {@code harness.timeout.tool-timeout}），不新造语义重复的键。
 *
 * @author Alex
 */
@Slf4j
@Component
public class ToolExecutorService {

    /**
     * 工具类别。每类一个独立隔离池；未知工具归入 {@link #DEFAULT}。
     */
    public enum ToolCategory {
        /** 检索类：本地知识库 / 联网搜索，IO 密集。 */
        SEARCH,
        /** 记忆类：长期记忆召回。 */
        MEMORY,
        /** 计算类：纯 CPU 的四则运算、取时间。 */
        COMPUTE,
        /** 代码执行类：沙箱运行，风险最高、最可能阻塞。 */
        CODE,
        /** 未分类工具。 */
        DEFAULT
    }

    /** 工具名 → 类别映射（大小写归一在 {@link #resolveCategory(String)} 中处理）。 */
    private static final Map<String, ToolCategory> TOOL_CATEGORY_MAP = Map.of(
            "knowledge_search", ToolCategory.SEARCH,
            "web_search", ToolCategory.SEARCH,
            "memory_search", ToolCategory.MEMORY,
            "current_time", ToolCategory.COMPUTE,
            "calculate", ToolCategory.COMPUTE,
            "run_code", ToolCategory.CODE
    );

    /** 池核心线程数（复用 HarnessRuntime 的配置键）。 */
    @Value("${harness.pool.core-size:8}")
    private int coreSize;

    /** 池最大线程数。 */
    @Value("${harness.pool.max-size:32}")
    private int maxSize;

    /** 有界队列容量。 */
    @Value("${harness.pool.queue-capacity:128}")
    private int queueCapacity;

    /** 空闲线程存活秒数。 */
    @Value("${harness.pool.keep-alive-seconds:60}")
    private long keepAliveSeconds;

    /** 单个工具执行的默认超时（毫秒），复用 harness.timeout.tool-timeout。 */
    @Value("${harness.timeout.tool-timeout:10000}")
    private long defaultTimeoutMs;

    /** 每类别的隔离池。 */
    private final Map<ToolCategory, ExecutorService> pools = new EnumMap<>(ToolCategory.class);

    /**
     * 初始化各类别线程池。{@link PostConstruct} 在依赖注入完成后调用。
     */
    @PostConstruct
    void initPools() {
        int core = Math.max(1, coreSize);
        int maxThreads = Math.max(maxSize, core);
        int queue = Math.max(1, queueCapacity);
        for (ToolCategory category : ToolCategory.values()) {
            pools.put(category, newPool(category, core, maxThreads, queue));
        }
        log.info("ToolExecutorService pools initialized: categories={}, core={}, max={}, queue={}, keepAlive={}s, defaultTimeout={}ms",
                pools.keySet(), core, maxThreads, queue, keepAliveSeconds, defaultTimeoutMs);
    }

    /**
     * 关闭所有隔离池。{@link PreDestroy} 在容器销毁时调用。
     */
    @PreDestroy
    void shutdownPools() {
        for (Map.Entry<ToolCategory, ExecutorService> entry : pools.entrySet()) {
            ExecutorService pool = entry.getValue();
            pool.shutdown();
            try {
                if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
            }
        }
        log.info("ToolExecutorService pools shut down");
    }

    /**
     * 构造单个类别的有界池。
     *
     * @param category   类别（用于线程命名）
     * @param core       核心线程数
     * @param maxThreads 最大线程数
     * @param queue      队列容量
     * @return 有界 {@link ThreadPoolExecutor}
     */
    private ThreadPoolExecutor newPool(ToolCategory category, int core, int maxThreads, int queue) {
        AtomicInteger seq = new AtomicInteger(1);
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "tool-" + category.name().toLowerCase(Locale.ROOT) + "-worker-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        return new ThreadPoolExecutor(
                core,
                maxThreads,
                keepAliveSeconds,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queue),
                factory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 在对应类别的隔离池中执行工具动作，带超时保护。
     *
     * @param toolName  工具名（用于分类、命名与结果构造）
     * @param action    实际执行逻辑，返回 {@link ToolResult}；为 null 视为成功空结果
     * @param timeoutMs 超时毫秒数；{@code <= 0} 时回退到默认超时
     * @return 工具结果：正常执行返回 action 的结果；超时返回 {@link ToolResult#timeout}；
     *         抛异常或池饱和返回 {@link ToolResult#failure}。绝不抛异常给调用方。
     */
    public ToolResult executeIsolated(String toolName, Supplier<ToolResult> action, long timeoutMs) {
        ToolCategory category = resolveCategory(toolName);
        ExecutorService pool = pools.get(category);
        if (pool == null) {
            return ToolResult.failure(toolName, "Tool executor pool not initialized for category " + category);
        }

        long effectiveTimeout = timeoutMs > 0 ? timeoutMs : defaultTimeoutMs;
        long start = System.currentTimeMillis();

        Callable<ToolResult> task = () -> {
            ToolResult result = action.get();
            return result != null ? result : ToolResult.success(toolName, null, null);
        };

        Future<ToolResult> future;
        try {
            future = pool.submit(task);
        } catch (RejectedExecutionException e) {
            log.warn("Tool [{}] rejected: pool [{}] saturated", toolName, category);
            return ToolResult.failure(toolName,
                    "Tool executor pool [" + category + "] saturated, request rejected");
        }

        try {
            ToolResult result = future.get(effectiveTimeout, TimeUnit.MILLISECONDS);
            if (result.getElapsedMs() <= 0) {
                result.setElapsedMs(System.currentTimeMillis() - start);
            }
            return result;
        } catch (TimeoutException e) {
            future.cancel(true);
            long elapsed = System.currentTimeMillis() - start;
            log.warn("Tool [{}] timed out after {}ms on pool [{}], future cancelled", toolName, elapsed, category);
            return ToolResult.timeout(toolName, elapsed);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return ToolResult.failure(toolName, "Tool execution interrupted: " + e.getMessage());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String message = cause.getMessage() != null ? cause.getMessage() : cause.toString();
            log.warn("Tool [{}] execution threw on pool [{}]: {}", toolName, category, message);
            return ToolResult.failure(toolName, message);
        }
    }

    /**
     * 解析工具所属类别。未知工具归入 {@link ToolCategory#DEFAULT}。
     *
     * @param toolName 工具名（大小写不敏感）
     * @return 类别（非 null）
     */
    public ToolCategory resolveCategory(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return ToolCategory.DEFAULT;
        }
        ToolCategory category = TOOL_CATEGORY_MAP.get(toolName.trim().toLowerCase(Locale.ROOT));
        return category != null ? category : ToolCategory.DEFAULT;
    }

    /**
     * 默认工具超时（毫秒），供 {@link ToolRegistry} 复用。
     *
     * @return 默认超时毫秒数
     */
    public long getDefaultTimeoutMs() {
        return defaultTimeoutMs;
    }

    /**
     * 暴露某类别池，供运维/测试观测。
     *
     * @param category 类别
     * @return 对应池，可能为 null（初始化前）
     */
    public ExecutorService getPool(ToolCategory category) {
        return pools.get(category);
    }
}
