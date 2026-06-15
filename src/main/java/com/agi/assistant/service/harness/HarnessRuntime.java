package com.agi.assistant.service.harness;

import com.agi.assistant.model.enums.TaskStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    private final ExecutorService executor;

    public HarnessRuntime(TimeoutConfig timeoutConfig,
                          RetryPolicy retryPolicy,
                          FallbackStrategy fallbackStrategy,
                          StateMachine stateMachine) {
        this.timeoutConfig = timeoutConfig;
        this.retryPolicy = retryPolicy;
        this.fallbackStrategy = fallbackStrategy;
        this.stateMachine = stateMachine;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("harness-worker-" + t.getId());
            return t;
        });
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
     *   <li>重试耗尽后降级：FAILED → FALLBACK → RUNNING</li>
     * </ol>
     *
     * @param task     要执行的任务
     * @param taskName 任务名称
     * @param timeout  超时时间（毫秒）
     * @param <T>      返回值类型
     * @return 任务执行结果
     */
    public <T> T execute(Callable<T> task, String taskName, long timeout) {
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
            log.warn("Task [{}] retries exhausted, attempting fallback", taskName);

            // 重试耗尽 → 尝试降级
            stateMachine.transition(taskName, TaskStatus.FAILED);
            stateMachine.transition(taskName, TaskStatus.FALLBACK);
            stateMachine.transition(taskName, TaskStatus.RUNNING);

            T fallbackResult = fallbackStrategy.executeWithFallback(
                    () -> {
                        throw new RuntimeException("No primary strategy available after retry exhaustion");
                    },
                    () -> {
                        log.info("Executing fallback for task [{}]", taskName);
                        return null;
                    }
            );

            if (fallbackResult != null) {
                stateMachine.transition(taskName, TaskStatus.COMPLETED);
            } else {
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
     * 在超时范围内执行任务。
     */
    private <T> T executeWithTimeout(Callable<T> task, long timeoutMs) throws Exception {
        Future<T> future = executor.submit(task);
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
