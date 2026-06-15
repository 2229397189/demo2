package com.agi.assistant.service.harness;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
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
     * 若所有重试均失败，则抛出最后一次异常。
     *
     * @param action 要执行的动作
     * @param policy 重试策略配置
     * @param <T>    返回值类型
     * @return 动作执行结果
     */
    public static <T> T executeWithRetry(Supplier<T> action, RetryPolicy policy) {
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
                log.warn("Attempt {}/{} failed: {}", attempt + 1, maxRetries + 1, e.getMessage());
            }
        }
        throw new RetryExhaustedException(
                "All " + (maxRetries + 1) + " attempts failed", lastException);
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
