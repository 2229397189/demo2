package com.agi.assistant.service.harness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RetryPolicy} 测试。
 * <p>
 * 核心是一条修复：「不是所有异常都值得重试」。
 * 修复前对超时也照重试，一次 5s 超时的检索最坏会变成 4 次尝试 + 1/2/4s 退避 ≈ 27s，
 * 而调用方（SSE 流式对话）本意只是「最多等 5 秒」。
 */
class RetryPolicyTest {

    /** 关闭等待，避免测试为了退避白等几秒 */
    private RetryPolicy fastPolicy(int maxRetries) {
        RetryPolicy policy = new RetryPolicy();
        policy.setMaxRetries(maxRetries);
        policy.setRetryDelay(0L);
        policy.setBackoffMultiplier(1.0);
        return policy;
    }

    @Test
    @DisplayName("首次成功即返回，不做多余重试")
    void successOnFirstAttempt() {
        AtomicInteger calls = new AtomicInteger();

        String result = RetryPolicy.executeWithRetry(() -> {
            calls.incrementAndGet();
            return "ok";
        }, fastPolicy(3));

        assertThat(result).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("可重试异常重试到上限，共尝试 maxRetries+1 次")
    void retryableExceptionRetriesToLimit() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> RetryPolicy.executeWithRetry(() -> {
            calls.incrementAndGet();
            throw new RuntimeException("boom");
        }, fastPolicy(2)))
                .isInstanceOf(RetryPolicy.RetryExhaustedException.class)
                .hasMessageContaining("3");

        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("中途恢复则不再重试")
    void recoversOnSecondAttempt() {
        AtomicInteger calls = new AtomicInteger();

        String result = RetryPolicy.executeWithRetry(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new RuntimeException("transient");
            }
            return "recovered";
        }, fastPolicy(3));

        assertThat(result).isEqualTo("recovered");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("参数非法不重试：输入不会因为重试而变合法")
    void illegalArgumentIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> RetryPolicy.executeWithRetry(() -> {
            calls.incrementAndGet();
            throw new IllegalArgumentException("bad input");
        }, fastPolicy(3)))
                .isInstanceOf(RetryPolicy.RetryExhaustedException.class)
                .hasMessageContaining("Non-retryable")
                .hasRootCauseInstanceOf(IllegalArgumentException.class);

        assertThat(calls.get()).as("非可重试异常只应尝试一次").isEqualTo(1);
    }

    @Test
    @DisplayName("超时不重试 —— 时间预算已用光，再等只会更慢")
    void timeoutIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> RetryPolicy.executeWithRetry(() -> {
            calls.incrementAndGet();
            throw new RuntimeException("wrapped", new TimeoutException("read timeout"));
        }, fastPolicy(3)))
                .isInstanceOf(RetryPolicy.RetryExhaustedException.class);

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("线程池饱和不重试 —— 重试只会加重排队")
    void rejectionIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> RetryPolicy.executeWithRetry(() -> {
            calls.incrementAndGet();
            throw new RejectedExecutionException("pool saturated");
        }, fastPolicy(3)))
                .isInstanceOf(RetryPolicy.RetryExhaustedException.class);

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("中断不重试 —— 调用方要收摊了")
    void interruptionIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> RetryPolicy.executeWithRetry(() -> {
            calls.incrementAndGet();
            throw new RuntimeException("wrapped", new InterruptedException("client gone"));
        }, fastPolicy(3)))
                .isInstanceOf(RetryPolicy.RetryExhaustedException.class);

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("isRetryable 语义：可重试的普通异常返回 true")
    void isRetryableSemantics() {
        assertThat(RetryPolicy.isRetryable(new IOException("connection reset"))).isTrue();
        assertThat(RetryPolicy.isRetryable(new RuntimeException("unknown"))).isTrue();

        assertThat(RetryPolicy.isRetryable(new TimeoutException())).isFalse();
        assertThat(RetryPolicy.isRetryable(new IllegalArgumentException())).isFalse();
        assertThat(RetryPolicy.isRetryable(new InterruptedException())).isFalse();
        assertThat(RetryPolicy.isRetryable(new RejectedExecutionException())).isFalse();

        // 必须沿 cause 链判断：HarnessRuntime 会包一层 RuntimeException
        assertThat(RetryPolicy.isRetryable(new RuntimeException(new TimeoutException()))).isFalse();
        assertThat(RetryPolicy.isRetryable(new RuntimeException("a", new IllegalStateException("b",
                new IllegalArgumentException("deep"))))).isFalse();

        // 自引用 cause 不能让判定陷入死循环
        RuntimeException selfCaused = new RuntimeException("self") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertThat(RetryPolicy.isRetryable(selfCaused)).isTrue();
    }
}
