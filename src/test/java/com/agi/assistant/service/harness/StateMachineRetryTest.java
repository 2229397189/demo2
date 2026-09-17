package com.agi.assistant.service.harness;

import com.agi.assistant.model.enums.TaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@code RETRYING} 可达性与 {@code StateMachine.remove} 清理的端到端测试。
 * <p>
 * 离线、不依赖 Spring 上下文、不花钱。通过继承 {@link StateMachine} 记录
 * <b>每一次真实的状态转移</b>，从而断言执行过程中确实观测到过 {@code RETRYING}
 * —— 而不是只断言最终状态。
 */
class StateMachineRetryTest {

    /** maxRetries=2 → 最多 3 次尝试（第 1 次 + 2 次重试），便于构造「前 2 次失败、第 3 次成功」 */
    private static final int MAX_RETRIES = 2;

    private RecordingStateMachine stateMachine;
    private HarnessRuntime harnessRuntime;

    @BeforeEach
    void setUp() {
        TimeoutConfig timeoutConfig = new TimeoutConfig();

        RetryPolicy retryPolicy = new RetryPolicy();
        retryPolicy.setMaxRetries(MAX_RETRIES);
        retryPolicy.setRetryDelay(0L);        // 关闭退避等待，测试不白等
        retryPolicy.setBackoffMultiplier(1.0);

        FallbackStrategy fallbackStrategy = new FallbackStrategy();
        stateMachine = new RecordingStateMachine();

        harnessRuntime = new HarnessRuntime(timeoutConfig, retryPolicy, fallbackStrategy, stateMachine);

        // @Value 字段在纯单元测试里不会被注入，手动设置后再初始化线程池，
        // 否则 ThreadPoolExecutor 会因 maximumPoolSize <= 0 而构造失败。
        ReflectionTestUtils.setField(harnessRuntime, "poolCoreSize", 2);
        ReflectionTestUtils.setField(harnessRuntime, "poolMaxSize", 4);
        ReflectionTestUtils.setField(harnessRuntime, "poolQueueCapacity", 8);
        ReflectionTestUtils.setField(harnessRuntime, "poolKeepAliveSeconds", 1L);
        harnessRuntime.initExecutor();
    }

    @AfterEach
    void tearDown() {
        harnessRuntime.shutdownExecutor();
    }

    @Test
    @DisplayName("重试期间确实到达 RETRYING（前 2 次抛异常，第 3 次成功）")
    void retryingIsReachableDuringRetry() {
        AtomicInteger attempts = new AtomicInteger();

        String result = harnessRuntime.execute(() -> {
            if (attempts.incrementAndGet() <= 2) {
                throw new RuntimeException("transient failure " + attempts.get());
            }
            return "ok";
        }, "retryTask", 5_000L);

        assertThat(result).isEqualTo("ok");
        assertThat(attempts.get()).as("前 2 次失败 + 第 3 次成功").isEqualTo(3);

        // 关键断言：执行过程中确实观测到了 RETRYING
        assertThat(stateMachine.transitions)
                .as("必须在真实执行过程中观测到 RETRYING，而不是只断言最终状态")
                .contains(TaskStatus.RETRYING);

        // 完整时序可确定性断言：每次重试都走 RUNNING -> FAILED -> RETRYING -> RUNNING
        assertThat(stateMachine.transitions).containsExactly(
                TaskStatus.RUNNING,
                TaskStatus.FAILED, TaskStatus.RETRYING, TaskStatus.RUNNING,
                TaskStatus.FAILED, TaskStatus.RETRYING, TaskStatus.RUNNING,
                TaskStatus.COMPLETED);

        assertThat(Collections.frequency(stateMachine.transitions, TaskStatus.RETRYING))
                .as("2 次重试 → 观测到 2 次 RETRYING")
                .isEqualTo(2);

        // 终态清理：remove 被调用且 key 不再存在
        assertThat(stateMachine.removed).contains("retryTask");
        assertThat(stateMachine.getAllStatuses()).doesNotContainKey("retryTask");
    }

    @Test
    @DisplayName("重试耗尽 → 终态 FAILED、返回 null，且状态被清理")
    void retriesExhaustedEndsInFailedAndCleansUp() {
        AtomicInteger attempts = new AtomicInteger();

        String result = harnessRuntime.execute(() -> {
            attempts.incrementAndGet();
            throw new RuntimeException("always fails");
        }, "exhaustedTask", 5_000L);

        assertThat(result).as("无降级策略时必须返回 null，不编造默认值").isNull();
        assertThat(attempts.get()).as("maxRetries=2 → 共尝试 3 次").isEqualTo(3);

        // 终态为 FAILED（取执行过程中最后一次观测到的状态）
        assertThat(stateMachine.transitions).isNotEmpty();
        assertThat(stateMachine.transitions.get(stateMachine.transitions.size() - 1))
                .as("重试耗尽后的终态必须是 FAILED")
                .isEqualTo(TaskStatus.FAILED);

        // 重试路径仍然真实可达
        assertThat(stateMachine.transitions).contains(TaskStatus.RETRYING);

        // remove 被调用，且 key 已从状态机移除
        assertThat(stateMachine.removed).contains("exhaustedTask");
        assertThat(stateMachine.getAllStatuses()).doesNotContainKey("exhaustedTask");
        assertThat(stateMachine.getStatus("exhaustedTask")).isEqualTo(TaskStatus.INITIALIZED);
    }

    @Test
    @DisplayName("remove 幂等：对不存在的 key 调用不抛异常")
    void removeIsIdempotentForMissingKey() {
        StateMachine plainStateMachine = new StateMachine();

        assertThatCode(() -> {
            plainStateMachine.remove("never-registered");
            plainStateMachine.remove("never-registered");
        }).doesNotThrowAnyException();

        assertThat(plainStateMachine.getStatus("never-registered")).isEqualTo(TaskStatus.INITIALIZED);
        assertThat(plainStateMachine.getAllStatuses()).doesNotContainKey("never-registered");
    }

    /**
     * 记录每一次真实状态转移与 remove 调用的 {@link StateMachine} 探针。
     */
    static class RecordingStateMachine extends StateMachine {

        /** 按发生顺序记录每一次被请求的目标状态 */
        final List<TaskStatus> transitions = Collections.synchronizedList(new ArrayList<>());

        /** 记录每一次 remove 的 taskName */
        final List<String> removed = Collections.synchronizedList(new ArrayList<>());

        @Override
        public TaskStatus transition(String taskName, TaskStatus targetState) {
            transitions.add(targetState);
            return super.transition(taskName, targetState);
        }

        @Override
        public void remove(String taskName) {
            removed.add(taskName);
            super.remove(taskName);
        }
    }
}
