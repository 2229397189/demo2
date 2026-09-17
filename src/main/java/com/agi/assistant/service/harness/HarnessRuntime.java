package com.agi.assistant.service.harness;

import com.agi.assistant.model.enums.TaskStatus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Harness 运行时
 * <p>
 * 统一编排超时、重试、降级和状态机四大子系统，
 * 为上层业务提供标准化的任务执行入口。
 */
@Slf4j
@Service
public class HarnessRuntime {

    private final TimeoutConfig timeoutConfig;
    private final RetryPolicy retryPolicy;
    private final FallbackStrategy fallbackStrategy;
    private final StateMachine stateMachine;

    /**
     * 工作线程池。必须有界 —— 此前用 {@code Executors.newCachedThreadPool()}，
     * 那是「来多少任务开多少线程」的无界池：网关被打满时每个请求都会新建线程，
     * 线程数随并发线性上升，最终把机器拖垮（且失败方式是 OOM，不是可观测的拒绝）。
     * <p>
     * 现在改成有界池 + 有界队列 + 明确的拒绝策略：过载时快速失败，
     * 由上层降级兜住，而不是把线程堆到崩溃。
     */
    private ExecutorService executor;

    @Value("${harness.pool.core-size:8}")
    private int poolCoreSize;

    @Value("${harness.pool.max-size:32}")
    private int poolMaxSize;

    @Value("${harness.pool.queue-capacity:128}")
    private int poolQueueCapacity;

    /** 空闲线程存活时间（秒），超过 coreSize 的线程在此时间后被回收 */
    @Value("${harness.pool.keep-alive-seconds:60}")
    private long poolKeepAliveSeconds;

    public HarnessRuntime(TimeoutConfig timeoutConfig,
                          RetryPolicy retryPolicy,
                          FallbackStrategy fallbackStrategy,
                          StateMachine stateMachine) {
        this.timeoutConfig = timeoutConfig;
        this.retryPolicy = retryPolicy;
        this.fallbackStrategy = fallbackStrategy;
        this.stateMachine = stateMachine;
    }

    @PostConstruct
    void initExecutor() {
        this.executor = new ThreadPoolExecutor(
                poolCoreSize,
                Math.max(poolMaxSize, poolCoreSize),
                poolKeepAliveSeconds,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(1, poolQueueCapacity)),
                r -> {
                    Thread t = new Thread(r);
                    t.setDaemon(true);
                    t.setName("harness-worker-" + t.getId());
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());

        log.info("HarnessRuntime thread pool initialized: core={}, max={}, queue={}, keepAlive={}s",
                poolCoreSize, poolMaxSize, poolQueueCapacity, poolKeepAliveSeconds);
    }

    @PreDestroy
    void shutdownExecutor() {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        log.info("HarnessRuntime thread pool shut down");
    }

    /**
     * 执行任务，带超时、重试和状态机管理。
     * <p>
     * 执行流程：
     * <ol>
     *   <li>状态：INITIALIZED → RUNNING</li>
     *   <li>在超时范围内执行任务（带重试）</li>
     *   <li>成功：RUNNING → COMPLETED</li>
     *   <li>失败后重试：FAILED → RETRYING → RUNNING</li>
     *   <li>重试耗尽后降级：FAILED → FALLBACK → RUNNING → COMPLETED/FAILED</li>
     * </ol>
     *
     * @param task     要执行的任务
     * @param taskName 任务名称
     * @param timeout  超时时间（毫秒）
     * @param <T>      返回值类型
     * @return 任务执行结果，降级后无可用结果时返回 null
     */
    public <T> T execute(Callable<T> task, String taskName, long timeout) {
        return execute(task, taskName, timeout, null);
    }

    /**
     * 执行任务，并注册一个真正的降级策略。
     * <p>
     * 修复说明：此前 {@link #execute(Callable, String, long)} 的降级分支是这样的 ——
     * 主策略写死成「必然抛异常」，备用策略写死成「返回 null」。也就是说它
     * 从来没有真正的降级能力，只是在走一遍状态机、顺便把异常吞掉。
     * 调用方既拿不到降级结果，也拿不到失败原因，只能收到一个莫名其妙的 null。
     * <p>
     * 现在降级策略由调用方传入：重试耗尽后执行 {@code fallbackSupplier}，
     * 拿到非 null 结果即视为降级成功（COMPLETED），否则标记 FAILED 并返回 null。
     *
     * @param task             要执行的任务
     * @param taskName         任务名称
     * @param timeout          超时时间（毫秒）
     * @param fallbackSupplier 降级策略；为 null 表示该任务没有降级方案
     * @param <T>              返回值类型
     * @return 任务执行结果或降级结果
     */
    public <T> T execute(Callable<T> task, String taskName, long timeout, Supplier<T> fallbackSupplier) {
        // 初始化状态
        stateMachine.reset(taskName);
        stateMachine.transition(taskName, TaskStatus.RUNNING);

        try {
            // 使用重试策略执行
            T result = RetryPolicy.executeWithRetry(() -> {
                try {
                    return executeWithTimeout(task, timeout);
                } catch (TimeoutException e) {
                    throw new RuntimeException("Task [" + taskName + "] timed out after " + timeout + "ms", e);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, retryPolicy);

            stateMachine.transition(taskName, TaskStatus.COMPLETED);
            log.info("Task [{}] completed successfully", taskName);
            return result;

        } catch (RetryPolicy.RetryExhaustedException e) {
            log.warn("Task [{}] retries exhausted ({}), attempting registered fallback",
                    taskName, e.getMessage());

            // 重试耗尽 → 尝试降级
            stateMachine.transition(taskName, TaskStatus.FAILED);
            stateMachine.transition(taskName, TaskStatus.FALLBACK);
            stateMachine.transition(taskName, TaskStatus.RUNNING);

            if (fallbackSupplier == null) {
                log.warn("Task [{}] has no fallback registered, returning null to caller", taskName);
                stateMachine.transition(taskName, TaskStatus.FAILED);
                return null;
            }

            Long fallbackStart = System.currentTimeMillis();
            T fallbackResult = fallbackStrategy.gracefulDegrade(
                    () -> {
                        log.info("Executing registered fallback for task [{}]", taskName);
                        return fallbackSupplier.get();
                    },
                    null,
                    taskName);

            if (fallbackResult != null) {
                log.info("Task [{}] recovered via fallback in {}ms",
                        taskName, System.currentTimeMillis() - fallbackStart);
                stateMachine.transition(taskName, TaskStatus.COMPLETED);
            } else {
                log.warn("Task [{}] fallback returned no usable result", taskName);
                stateMachine.transition(taskName, TaskStatus.FAILED);
            }
            return fallbackResult;
        }
    }

    /**
     * 使用默认超时执行任务。
     *
     * @param task     要执行的任务
     * @param taskName 任务名称
     * @param <T>      返回值类型
     * @return 任务执行结果
     */
    public <T> T execute(Callable<T> task, String taskName) {
        return execute(task, taskName, timeoutConfig.getLlmTimeout());
    }

    /**
     * 获取指定任务的当前状态。
     *
     * @param taskName 任务名称
     * @return 当前任务状态
     */
    public TaskStatus getStatus(String taskName) {
        return stateMachine.getStatus(taskName);
    }

    /**
     * 获取所有任务的状态。
     *
     * @return 任务状态映射
     */
    public java.util.Map<String, TaskStatus> getAllStatuses() {
        return stateMachine.getAllStatuses();
    }

    /**
     * 线程池运行快照，供运维观测（活跃线程 / 队列积压 / 已完成任务数）。
     */
    public java.util.Map<String, Object> poolStats() {
        java.util.Map<String, Object> stats = new java.util.LinkedHashMap<>();
        if (executor instanceof ThreadPoolExecutor tpe) {
            stats.put("activeCount", tpe.getActiveCount());
            stats.put("poolSize", tpe.getPoolSize());
            stats.put("corePoolSize", tpe.getCorePoolSize());
            stats.put("maxPoolSize", tpe.getMaximumPoolSize());
            stats.put("queueSize", tpe.getQueue().size());
            stats.put("queueCapacity", poolQueueCapacity);
            stats.put("completedTaskCount", tpe.getCompletedTaskCount());
        }
        return stats;
    }

    /**
     * 在超时范围内执行任务。
     * <p>
     * 线程池饱和（队列满且线程数已达上限）时抛出 {@link RejectedExecutionException}，
     * 由 {@link RetryPolicy#isRetryable(Throwable)} 判定为不可重试并直接进入降级 ——
     * 对过载场景重试毫无意义，只会让排队更长。
     */
    private <T> T executeWithTimeout(Callable<T> task, long timeoutMs) throws Exception {
        Future<T> future;
        try {
            future = executor.submit(task);
        } catch (RejectedExecutionException e) {
            throw new RejectedExecutionException(
                    "Harness thread pool saturated, task rejected", e);
        }

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        } catch (Exception e) {
            future.cancel(true);
            throw e;
        }
    }
}
