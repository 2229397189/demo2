package com.agi.assistant.service.agent;

import com.agi.assistant.model.entity.SearchResult;
import com.agi.assistant.model.enums.RetrievalStrategy;
import com.agi.assistant.service.rag.HybridRetrievalService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Multi-source racing strategy service.
 * <p>
 * Races multiple search sources, models, or retrieval strategies in parallel
 * and returns the first successful result. Uses CompletableFuture.anyOf()
 * for competitive execution.
 */
@Slf4j
@Service
public class RaceStrategy {

    private static final long DEFAULT_TIMEOUT_SECONDS = 30;

    private final Executor raceExecutor;
    private final HybridRetrievalService hybridRetrievalService;
    private final WebClient openAiWebClient;
    private final ObjectMapper objectMapper;

    public RaceStrategy(HybridRetrievalService hybridRetrievalService,
                        WebClient openAiWebClient,
                        @Qualifier("raceExecutor") Executor raceExecutor) {
        this.raceExecutor = raceExecutor;
        this.hybridRetrievalService = hybridRetrievalService;
        this.openAiWebClient = openAiWebClient;
        this.objectMapper = new ObjectMapper();
    }

    // ----------------------------------------------------------------
    //  Public API
    // ----------------------------------------------------------------

    /**
     * Race multiple search sources and return the first successful result.
     * <p>
     * Each source is queried in parallel. The first source to return a
     * non-empty result wins. If all sources fail, an empty list is returned.
     *
     * @param sources the list of search sources to race
     * @param query   the search query
     * @return the first successful search results
     */
    @SuppressWarnings("unchecked")
    public List<SearchResult> raceSearch(List<SearchSource> sources, String query) {
        if (sources == null || sources.isEmpty() || query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        log.info("Racing {} search sources for query: '{}'",
                sources.size(), query.length() > 50 ? query.substring(0, 50) + "..." : query);

        List<CompletableFuture<List<SearchResult>>> futures = new ArrayList<>();
        for (SearchSource source : sources) {
            CompletableFuture<List<SearchResult>> future = CompletableFuture.supplyAsync(
                    () -> executeSearchSource(source, query), raceExecutor);
            futures.add(future);
        }

        // Race: return first non-empty result
        try {
            CompletableFuture<Object> race = CompletableFuture.anyOf(
                    futures.toArray(new CompletableFuture[0]));

            Object result = race.get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (result instanceof List<?> resultList && !resultList.isEmpty()) {
                log.info("Race search won by source, returned {} results", resultList.size());
                return (List<SearchResult>) resultList;
            }
        } catch (Exception e) {
            log.warn("Race search anyOf failed: {}", e.getMessage());
        }

        // Fallback: collect all completed results
        List<SearchResult> fallback = new ArrayList<>();
        for (CompletableFuture<List<SearchResult>> future : futures) {
            try {
                if (future.isDone() && !future.isCompletedExceptionally()) {
                    List<SearchResult> partial = future.getNow(Collections.emptyList());
                    if (partial != null && !partial.isEmpty()) {
                        fallback.addAll(partial);
                        break;
                    }
                }
            } catch (Exception ignored) {
                // skip failed futures
            }
        }

        if (fallback.isEmpty()) {
            log.warn("All search sources failed for query: '{}'", query);
        }

        return fallback;
    }

    /**
     * Race multiple models with the same prompt and return the first response.
     * <p>
     * Each model is called in parallel. The first model to return a valid
     * response wins.
     *
     * @param models the list of model configurations to race
     * @param prompt the prompt to send to each model
     * @return the first successful model response
     */
    @SuppressWarnings("unchecked")
    public String raceModel(List<ModelConfig> models, String prompt) {
        if (models == null || models.isEmpty() || prompt == null || prompt.isBlank()) {
            return "";
        }

        log.info("Racing {} models with prompt length: {}", models.size(), prompt.length());

        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (ModelConfig model : models) {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(
                    () -> callModel(model, prompt), raceExecutor);
            futures.add(future);
        }

        try {
            CompletableFuture<Object> race = CompletableFuture.anyOf(
                    futures.toArray(new CompletableFuture[0]));

            Object result = race.get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (result instanceof String response && !response.isBlank()) {
                log.info("Race model won: response length={}", response.length());
                return response;
            }
        } catch (Exception e) {
            log.warn("Race model anyOf failed: {}", e.getMessage());
        }

        // Fallback: collect first completed
        for (CompletableFuture<String> future : futures) {
            try {
                if (future.isDone() && !future.isCompletedExceptionally()) {
                    String partial = future.getNow("");
                    if (partial != null && !partial.isBlank()) {
                        return partial;
                    }
                }
            } catch (Exception ignored) {
                // skip
            }
        }

        log.warn("All models failed for prompt");
        return "";
    }

    /**
     * Race multiple retrieval strategies and return the first successful result.
     * <p>
     * Each retrieval strategy is executed in parallel using the HybridRetrievalService.
     *
     * @param strategies the list of retrieval strategies to race
     * @param query      the search query
     * @return the first successful retrieval results
     */
    @SuppressWarnings("unchecked")
    public List<SearchResult> raceRetrieve(List<RetrievalStrategy> strategies, String query) {
        if (strategies == null || strategies.isEmpty() || query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        log.info("Racing {} retrieval strategies for query: '{}'",
                strategies.size(), query.length() > 50 ? query.substring(0, 50) + "..." : query);

        List<CompletableFuture<List<SearchResult>>> futures = new ArrayList<>();
        for (RetrievalStrategy strategy : strategies) {
            CompletableFuture<List<SearchResult>> future = CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            List<SearchResult> results = hybridRetrievalService.retrieve(
                                    query, strategy.name(), 10);
                            if (results != null && !results.isEmpty()) {
                                log.debug("Strategy {} returned {} results", strategy, results.size());
                                return results;
                            }
                        } catch (Exception e) {
                            log.debug("Strategy {} failed: {}", strategy, e.getMessage());
                        }
                        return Collections.<SearchResult>emptyList();
                    }, raceExecutor);
            futures.add(future);
        }

        try {
            CompletableFuture<Object> race = CompletableFuture.anyOf(
                    futures.toArray(new CompletableFuture[0]));

            Object result = race.get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (result instanceof List<?> resultList && !resultList.isEmpty()) {
                log.info("Race retrieve won, returned {} results", resultList.size());
                return (List<SearchResult>) resultList;
            }
        } catch (Exception e) {
            log.warn("Race retrieve anyOf failed: {}", e.getMessage());
        }

        // Fallback
        for (CompletableFuture<List<SearchResult>> future : futures) {
            try {
                if (future.isDone() && !future.isCompletedExceptionally()) {
                    List<SearchResult> partial = future.getNow(Collections.emptyList());
                    if (partial != null && !partial.isEmpty()) {
                        return partial;
                    }
                }
            } catch (Exception ignored) {
                // skip
            }
        }

        log.warn("All retrieval strategies failed for query");
        return Collections.emptyList();
    }

    // ----------------------------------------------------------------
    //  Internal
    // ----------------------------------------------------------------

    private List<SearchResult> executeSearchSource(SearchSource source, String query) {
        try {
            switch (source.getType()) {
                case "dense":
                    return hybridRetrievalService.executeDense(query, source.getTopK());
                case "sparse":
                    return hybridRetrievalService.executeSparse(query, source.getTopK());
                case "graph":
                    return hybridRetrievalService.executeGraph(query, source.getTopK());
                case "hybrid":
                    return hybridRetrievalService.retrieve(query, "HYBRID", source.getTopK());
                default:
                    log.warn("Unknown search source type: {}", source.getType());
                    return Collections.emptyList();
            }
        } catch (Exception e) {
            log.debug("Search source [{}] failed: {}", source.getType(), e.getMessage());
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private String callModel(ModelConfig model, String prompt) {
        try {
            Map<String, Object> requestBody = Map.of(
                    "model", model.getModelName(),
                    "messages", List.of(Map.of("role", "user", "content", prompt)),
                    "temperature", model.getTemperature(),
                    "max_tokens", model.getMaxTokens()
            );

            String responseStr = openAiWebClient.post()
                    .uri("/v1/chat/completions")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(model.getTimeoutSeconds()))
                    .block();

            if (responseStr == null) {
                return "";
            }

            Map<String, Object> response = objectMapper.readValue(responseStr, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) {
                return "";
            }

            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            if (message == null || message.get("content") == null) {
                return "";
            }

            return (String) message.get("content");

        } catch (Exception e) {
            log.debug("Model [{}] call failed: {}", model.getModelName(), e.getMessage());
            return "";
        }
    }

    // ----------------------------------------------------------------
    //  Inner Classes
    // ----------------------------------------------------------------

    /**
     * Configuration for a search source to race.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchSource {
        /**
         * Source type: "dense", "sparse", "graph", "hybrid"
         */
        private String type;

        /**
         * Maximum number of results to return
         */
        @Builder.Default
        private int topK = 10;
    }

    /**
     * Configuration for a model to race.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelConfig {
        /**
         * The model name/identifier
         */
        private String modelName;

        /**
         * Temperature for generation
         */
        @Builder.Default
        private double temperature = 0.7;

        /**
         * Maximum tokens to generate
         */
        @Builder.Default
        private int maxTokens = 2000;

        /**
         * Timeout in seconds for this model
         */
        @Builder.Default
        private int timeoutSeconds = 30;
    }
}
