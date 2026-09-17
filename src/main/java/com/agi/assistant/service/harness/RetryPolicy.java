package com.agi.assistant.service.harness;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * 通用重试策略
 * <p>
 * 支持最大重试次数、基础延迟、指数退避乘数，
 * 并提供泛型重试执行方法。
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "harness.retry")
public class RetryPolicy {

    /** 最大重试次数 */
    private int maxRetries = 3;

    /** 基础重试延迟（毫秒） */
    private long retryDelay = 1_000L;

    /** 退避乘数 */
    private double backoffMultiplier = 2.0;

    @PostConstruct
    public void init() {
        log.info("RetryPolicy initialized: maxRetries={}, retryDelay={}ms, backoffMultiplier={}",
                maxRetries, retryDelay, backoffMultiplier);
    }

    /**
     * 使用重试策略执行给定动作。
     * <p>
     * 若动作抛出异常，则按照退避策略重试，直到达到最大重试次数。
     * 若所有重试均失败，则抛出 {@link RetryExhaustedException}。
     * <p>
     * 注意：不是所有异常都值得重试，见 {@link #isRetryable(Throwable)}。
     * 修复前这里对「超时」也照样重试 —— 一次 5s 超时的检索在最坏情况下
     * 会变成 4 次尝试 + 1s/2s/4s 退避 ≈ 27s，而调用方（SSE 流式对话）本意
     * 只是「最多等 5 秒」。现在超时会直接进入降级分支。
     *
     * @param action 要执行的动作
     * @param policy 重试策略配置
     * @param <T>    返回值类型
     * @return 动作执行结果
     */
    public static <T> T executeWithRetry(Supplier<T> action, RetryPolicy policy) {
        return executeWithRetry(action, policy, null);
    }

    /**
     * 使用重试策略执行给定动作，并在每次「决定重试、尚未发起下一次」之前回调 {@code onRetry}。
     * <p>
     * 该回调存在的根因：重试循环完全封装在本方法内部，外界没有任何时机介入，
     * 导致 {@code RETRYING} 状态永远观测不到。现在上层（如 {@code HarnessRuntime}）
     * 可以在重试真正发生的那一刻驱动状态机进入 {@code RETRYING}。
     * <p>
     * 回调是纯观测埋点：它抛出的任何异常都会被吞掉并降级为 debug 日志，
     * <b>绝不影响重试主流程</b>。
     *
     * @param action  要执行的动作
     * @param policy  重试策略配置
     * @param onRetry 每次决定重试时（发起下一次之前）回调，参数为「当前已失败的次数」；可为 null
     * @param <T>     返回值类型
     * @return 动作执行结果
     */
    public static <T> T executeWithRetry(Supplier<T> action, RetryPolicy policy, IntConsumer onRetry) {
        int maxRetries = policy.getMaxRetries();
        long retryDelay = policy.getRetryDelay();
        double backoffMultiplier = policy.getBackoffMultiplier();

        Exception lastException = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                if (attempt > 0) {
                    long delay = (long) (retryDelay * Math.pow(backoffMultiplier, attempt - 1));
                    log.debug("Retry attempt {}/{}, waiting {}ms", attempt, maxRetries, delay);
                    Thread.sleep(delay);
                }
                return action.get();
            } catch (Exception e) {
                lastException = e;

                if (!isRetryable(e)) {
                    log.warn("Attempt {}/{} failed with non-retryable error [{}: {}], aborting retries",
                            attempt + 1, maxRetries + 1, e.getClass().getSimpleName(), e.getMessage());
                    throw new RetryExhaustedException(
                            "Non-retryable failure after " + (attempt + 1) + " attempt(s): " + e.getMessage(), e);
                }

                log.warn("Attempt {}/{} failed: {}", attempt + 1, maxRetries + 1, e.getMessage());

                // 决定要重试：在发起下一次尝试（进入下一次循环、执行退避等待）之前回调。
                if (attempt < maxRetries && onRetry != null) {
                    notifyRetry(onRetry, attempt + 1);
                }
            }
        }
        throw new RetryExhaustedException(
                "All " + (maxRetries + 1) + " attempts failed", lastException);
    }

    /**
     * 触发重试回调；回调异常被吞掉，仅记 debug，保证观测埋点不影响重试主流程。
     *
     * @param onRetry        重试回调
     * @param failedAttempts 当前已失败的次数
     */
    private static void notifyRetry(IntConsumer onRetry, int failedAttempts) {
        try {
            onRetry.accept(failedAttempts);
        } catch (Exception e) {
            log.debug("Retry callback failed (ignored): {}", e.getMessage());
        }
    }

    /**
     * 判断异常是否值得重试。
     * <p>
     * 需要沿着 cause 链往下看 —— HarnessRuntime 会把 TimeoutException 包装成
     * RuntimeException 再交给重试策略，只检查最外层会漏判。
     * <p>
     * 不可重试的情形：
     * <ul>
     *   <li>超时：时间预算已经用光了，再等只会更慢</li>
     *   <li>参数非法：输入不会因为重试而变合法</li>
     *   <li>中断：调用方要收摊了（如 SSE 客户端断开、优雅停机）</li>
     *   <li>拒绝执行：线程池已饱和，重试只会加重排队</li>
     * </ul>
     *
     * @param throwable 待判定的异常
     * @return true 表示可以重试
     */
    public static boolean isRetryable(Throwable throwable) {
        Throwable current = throwable;
        int guard = 0;
        while (current != null && guard++ < 16) {
            if (current instanceof java.util.concurrent.TimeoutException
                    || current instanceof IllegalArgumentException
                    || current instanceof InterruptedException
                    || current instanceof java.util.concurrent.RejectedExecutionException) {
                return false;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return true;
    }

    /**
     * 重试耗尽异常
     */
    public static class RetryExhaustedException extends RuntimeException {
        public RetryExhaustedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
