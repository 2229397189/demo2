package com.agi.assistant.service.harness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FallbackStrategy#executeChain} 测试。
 * <p>
 * 离线、不依赖 Spring 上下文、不花钱。核心断言：
 * <ul>
 *   <li>primary 成功即返回，任何 tier 都不被调用；</li>
 *   <li>primary 失败后按顺序尝试 tier，命中第一个成功者；</li>
 *   <li>全部失败返回 null（绝不返回空列表 / 空对象等编造值）；</li>
 *   <li>每一层都真的按顺序被尝试过。</li>
 * </ul>
 */
class FallbackStrategyTest {

    private final FallbackStrategy fallbackStrategy = new FallbackStrategy();

    @Test
    @DisplayName("primary 成功时直接返回，tier 一个都不被调用")
    void primarySuccessSkipsAllTiers() {
        List<String> calls = new ArrayList<>();
        AtomicInteger tier1Calls = new AtomicInteger();
        AtomicInteger tier2Calls = new AtomicInteger();

        String result = fallbackStrategy.executeChain(
                () -> {
                    calls.add("primary");
                    return "primary-result";
                },
                List.of(
                        () -> {
                            calls.add("tier1");
                            tier1Calls.incrementAndGet();
                            return "t1";
                        },
                        () -> {
                            calls.add("tier2");
                            tier2Calls.incrementAndGet();
                            return "t2";
                        }),
                "primarySuccess");

        assertThat(result).isEqualTo("primary-result");
        assertThat(calls).containsExactly("primary");
        assertThat(tier1Calls.get()).as("tier1 不应被调用").isZero();
        assertThat(tier2Calls.get()).as("tier2 不应被调用").isZero();
    }

    @Test
    @DisplayName("primary 失败 → 命中第一个能成功的 tier")
    void primaryFailureRecoversAtFirstSuccessfulTier() {
        List<String> calls = new ArrayList<>();

        String result = fallbackStrategy.executeChain(
                () -> {
                    calls.add("primary");
                    throw new RuntimeException("boom");
                },
                List.of(
                        () -> {
                            calls.add("tier1");
                            throw new RuntimeException("tier1 down");
                        },
                        () -> {
                            calls.add("tier2");
                            return "tier2-ok";
                        },
                        () -> {
                            calls.add("tier3");
                            return "tier3-ok";
                        }),
                "recoverAtTier2");

        assertThat(result).isEqualTo("tier2-ok");
        assertThat(calls).as("命中 tier2 后不应再尝试 tier3").containsExactly("primary", "tier1", "tier2");
    }

    @Test
    @DisplayName("全部失败 → 返回 null（绝不返回空列表 / 空对象）")
    void allTiersFailReturnsNull() {
        List<String> calls = new ArrayList<>();

        String result = fallbackStrategy.executeChain(
                () -> {
                    calls.add("primary");
                    throw new RuntimeException("boom");
                },
                List.of(
                        () -> {
                            calls.add("tier1");
                            throw new RuntimeException("tier1 fail");
                        },
                        () -> {
                            calls.add("tier2");
                            throw new RuntimeException("tier2 fail");
                        }),
                "allFail");

        assertThat(result).as("全失败必须返回 null，而不是编造的默认对象").isNull();
        assertThat(result).isNotEqualTo("");
        assertThat(calls).containsExactly("primary", "tier1", "tier2");
    }

    @Test
    @DisplayName("tiers 为 null 或空 → primary 失败即返回 null")
    void nullOrEmptyTiersReturnsNull() {
        String nullTiersResult = fallbackStrategy.executeChain(
                () -> {
                    throw new RuntimeException("boom");
                }, null, "nullTiers");
        assertThat(nullTiersResult).isNull();

        String emptyTiersResult = fallbackStrategy.executeChain(
                () -> {
                    throw new RuntimeException("boom");
                }, Collections.emptyList(), "emptyTiers");
        assertThat(emptyTiersResult).isNull();
    }

    @Test
    @DisplayName("executeChain 严格按顺序尝试每一层")
    void tiersTriedInOrder() {
        List<String> order = new ArrayList<>();

        fallbackStrategy.executeChain(
                () -> {
                    order.add("primary");
                    throw new RuntimeException("p");
                },
                List.of(
                        () -> {
                            order.add("tier1");
                            throw new RuntimeException("t1");
                        },
                        () -> {
                            order.add("tier2");
                            throw new RuntimeException("t2");
                        },
                        () -> {
                            order.add("tier3");
                            throw new RuntimeException("t3");
                        }),
                "order");

        assertThat(order).containsExactly("primary", "tier1", "tier2", "tier3");
    }
}
