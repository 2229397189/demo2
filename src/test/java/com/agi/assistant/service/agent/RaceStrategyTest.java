package com.agi.assistant.service.agent;

import com.agi.assistant.model.entity.SearchResult;
import com.agi.assistant.model.enums.RetrievalStrategy;
import com.agi.assistant.service.rag.HybridRetrievalService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link RaceStrategy} 测试。
 * <p>
 * 核心回归点：竞速的语义必须是「第一个产出<b>可用</b>结果的获胜」，
 * 而不是「第一个<b>完成</b>的获胜」。修复前用 {@code CompletableFuture.anyOf}，
 * 一个很快返回空结果的源（图检索不可用）会被判为赢家，整轮竞速直接判死 ——
 * 明明有另一路能拿到数据。
 * <p>
 * 第二个回归点：全员都返回空时，修复前无人 complete winner，
 * 调用方只能干等满 30s 超时。现在全员跑完即收口。
 */
class RaceStrategyTest {

    private ExecutorService executor;
    private HybridRetrievalService hybridRetrievalService;
    private RaceStrategy raceStrategy;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(4);
        hybridRetrievalService = mock(HybridRetrievalService.class);
        raceStrategy = new RaceStrategy(
                hybridRetrievalService, mock(WebClient.class), executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private static SearchResult hit(String content) {
        return SearchResult.builder()
                .title("t-" + content)
                .content(content)
                .source("test")
                .score(0.9)
                .build();
    }

    private static RaceStrategy.SearchSource source(String type) {
        return RaceStrategy.SearchSource.builder().type(type).topK(10).build();
    }

    // ──────────────────────────────────────────────────────────────
    //  核心回归
    // ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("快速返回空结果的源不能赢 —— 慢但真正有数据的源才该获胜")
    void fastEmptySourceMustNotWinTheRace() {
        // 稀疏检索很快但降级为空
        when(hybridRetrievalService.executeSparse(eq("q"), anyInt())).thenAnswer(inv -> {
            Thread.sleep(50);
            return List.of();
        });
        // 图检索直接失败
        when(hybridRetrievalService.executeGraph(eq("q"), anyInt()))
                .thenThrow(new RuntimeException("neo4j down"));
        // 稠密检索慢，但确实有结果
        when(hybridRetrievalService.executeDense(eq("q"), anyInt())).thenAnswer(inv -> {
            Thread.sleep(300);
            return List.of(hit("dense-hit"));
        });

        List<SearchResult> results = raceStrategy.raceSearch(
                List.of(source("sparse"), source("graph"), source("dense")), "q");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getContent()).isEqualTo("dense-hit");
    }

    @Test
    @DisplayName("全员无结果时立即收口，不干等满超时")
    void allSourcesEmptyReturnsPromptly() {
        when(hybridRetrievalService.executeSparse(anyString(), anyInt())).thenAnswer(inv -> {
            Thread.sleep(30);
            return List.of();
        });
        when(hybridRetrievalService.executeDense(anyString(), anyInt())).thenAnswer(inv -> {
            Thread.sleep(30);
            return List.of();
        });

        long start = System.nanoTime();
        List<SearchResult> results = raceStrategy.raceSearch(
                List.of(source("sparse"), source("dense")), "q");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(results).isEmpty();
        // 修复前这里会一直等到 30s 超时才返回
        assertThat(elapsedMs).isLessThan(5_000);
    }

    @Test
    @DisplayName("部分源抛异常不影响整体：其余源照常产出")
    void failingSourceDoesNotPoisonTheRace() {
        when(hybridRetrievalService.executeDense(eq("q"), anyInt()))
                .thenThrow(new RuntimeException("milvus down"));
        when(hybridRetrievalService.executeSparse(eq("q"), anyInt())).thenAnswer(inv -> {
            Thread.sleep(150);
            return List.of(hit("sparse-hit"));
        });
        when(hybridRetrievalService.executeGraph(eq("q"), anyInt())).thenAnswer(inv -> {
            Thread.sleep(120);
            return List.of(hit("graph-hit"));
        });

        List<SearchResult> results = raceStrategy.raceSearch(
                List.of(source("dense"), source("graph"), source("sparse")), "q");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getContent()).isIn("sparse-hit", "graph-hit");
    }

    @Test
    @DisplayName("未知源类型返回空，不计入赢家")
    void unknownSourceTypeYieldsEmpty() {
        when(hybridRetrievalService.executeSparse(eq("q"), anyInt()))
                .thenReturn(List.of(hit("sparse-hit")));

        List<SearchResult> results = raceStrategy.raceSearch(
                List.of(source("telepathy"), source("sparse")), "q");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getContent()).isEqualTo("sparse-hit");
    }

    // ──────────────────────────────────────────────────────────────
    //  参数守卫
    // ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("空查询 / 空源列表直接返回空，不触发检索")
    void blankInputsShortCircuit() {
        assertThat(raceStrategy.raceSearch(List.of(source("dense")), "")).isEmpty();
        assertThat(raceStrategy.raceSearch(List.of(source("dense")), null)).isEmpty();
        assertThat(raceStrategy.raceSearch(List.of(source("dense")), "   ")).isEmpty();
        assertThat(raceStrategy.raceSearch(List.of(), "q")).isEmpty();
        assertThat(raceStrategy.raceSearch(null, "q")).isEmpty();

        verifyNoInteractions(hybridRetrievalService);
    }

    // ──────────────────────────────────────────────────────────────
    //  raceRetrieve / raceModel
    // ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("多检索策略竞速：空结果策略不赢，有结果的策略获胜")
    void raceRetrievePicksFirstUsableStrategy() {
        when(hybridRetrievalService.retrieve(eq("q"), eq("SPARSE"), anyInt()))
                .thenAnswer(inv -> {
                    Thread.sleep(40);
                    return List.of();
                });
        when(hybridRetrievalService.retrieve(eq("q"), eq("HYBRID"), anyInt()))
                .thenAnswer(inv -> {
                    Thread.sleep(250);
                    return List.of(hit("hybrid-hit"));
                });

        List<SearchResult> results = raceStrategy.raceRetrieve(
                List.of(RetrievalStrategy.SPARSE, RetrievalStrategy.HYBRID), "q");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getContent()).isEqualTo("hybrid-hit");
    }

    @Test
    @DisplayName("所有检索策略都失败 → 返回空而不是抛异常")
    void raceRetrieveAllFailedReturnsEmpty() {
        when(hybridRetrievalService.retrieve(anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("all down"));

        assertThat(raceStrategy.raceRetrieve(
                List.of(RetrievalStrategy.DENSE, RetrievalStrategy.GRAPH), "q")).isEmpty();
    }

    @Test
    @DisplayName("raceRetrieve 参数守卫")
    void raceRetrieveGuards() {
        assertThat(raceStrategy.raceRetrieve(List.of(RetrievalStrategy.DENSE), "")).isEmpty();
        assertThat(raceStrategy.raceRetrieve(null, "q")).isEmpty();
        assertThat(raceStrategy.raceRetrieve(List.of(), "q")).isEmpty();

        verifyNoInteractions(hybridRetrievalService);
    }

    @Test
    @DisplayName("所有模型都失败 → 返回空串，且不干等超时")
    void raceModelReturnsEmptyWhenAllModelsFail() {
        // WebClient 是裸 mock，调用链会在 callModel 内部失败并被捕获 → 每个模型都返回空串
        long start = System.nanoTime();
        String winner = raceStrategy.raceModel(List.of(
                RaceStrategy.ModelConfig.builder().modelName("m1").build(),
                RaceStrategy.ModelConfig.builder().modelName("m2").build()), "prompt");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(winner).isEmpty();
        assertThat(elapsedMs).isLessThan(5_000);
    }

    @Test
    @DisplayName("raceModel 参数守卫")
    void raceModelGuards() {
        assertThat(raceStrategy.raceModel(List.of(), "prompt")).isEmpty();
        assertThat(raceStrategy.raceModel(null, "prompt")).isEmpty();
        assertThat(raceStrategy.raceModel(
                List.of(RaceStrategy.ModelConfig.builder().modelName("m").build()), "  ")).isEmpty();
    }
}
