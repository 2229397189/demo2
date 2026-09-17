package com.agi.assistant.service.evaluation;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.agi.assistant.config.OpenAIConfig;
import com.agi.assistant.service.evaluation.llm.LlmJudge;
import com.agi.assistant.service.rag.EmbeddingService;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 生成评估器（真实 RAGAS 机制）。
 * <p>
 * 四个指标的实现机制：
 * <ul>
 *   <li><b>Faithfulness</b>：把答案分解为原子 claim → 逐条核验是否被检索上下文支持 →
 *       被支持 claim 占比（{@link RagasScorer#faithfulness}）。</li>
 *   <li><b>Answer Relevancy</b>：由答案反向生成问题 → 与原问题取 embedding 算余弦均值；
 *       embedding 不可用时回退本地 Jaccard（{@link RagasScorer#answerRelevancyJaccard}）。</li>
 *   <li><b>Context Precision</b>：逐个 chunk 判定是否与问题相关 → 计算 AP@K
 *       （{@link RagasScorer#averagePrecision}）。</li>
 *   <li><b>Context Recall</b>：把期望答案分解为 golden claim → 用检索上下文整体判定是否覆盖 →
 *       被覆盖占比（{@link RagasScorer#contextRecall}）。</li>
 * </ul>
 * 子判断（LLM）与数学聚合（纯函数 {@link RagasScorer}）彻底分离，便于离线单测。
 * <p>
 * <b>绝不编造数字</b>：LLM / embedding 不可用或解析失败时该指标返回 {@code -1.0}，
 * 且任一指标失败不影响其他三个。{@code evaluation.ragas.enabled=false} 时退化为旧的
 * 单 prompt 打分路径（见 {@link #scoreFaithfulnessLegacy} 等）。
 */
@Slf4j
@Service
public class GenerationEvaluator {

    /** 反向生成问题的数量（RAGAS Answer Relevancy 默认采样数）。 */
    private static final int REVERSED_QUESTION_COUNT = 3;

    /** 指标不可用哨兵值。 */
    private static final double UNAVAILABLE = RagasScorer.UNAVAILABLE;

    private final WebClient openAiWebClient;
    private final OpenAIConfig openAIConfig;
    private final ObjectMapper objectMapper;
    private final LlmJudge llmJudge;
    private final EmbeddingService embeddingService;

    /** 是否启用真实 RAGAS 机制；false 时走旧式单 prompt 打分。 */
    private final boolean ragasEnabled;

    public GenerationEvaluator(WebClient openAiWebClient,
                               OpenAIConfig openAIConfig,
                               ObjectMapper objectMapper,
                               LlmJudge llmJudge,
                               EmbeddingService embeddingService,
                               @Value("${evaluation.ragas.enabled:true}") boolean ragasEnabled) {
        this.openAiWebClient = openAiWebClient;
        this.openAIConfig = openAIConfig;
        this.objectMapper = objectMapper;
        this.llmJudge = llmJudge;
        this.embeddingService = embeddingService;
        this.ragasEnabled = ragasEnabled;
    }

    /**
     * 评估生成质量。
     *
     * @param question       原始问题
     * @param answer         生成的答案
     * @param contexts       检索到的上下文列表
     * @param expectedAnswer 期望答案（可为 null，此时跳过 Context Recall 评估）
     * @return 生成指标
     */
    public GenerationMetrics evaluate(String question, String answer,
                                      List<String> contexts, String expectedAnswer) {
        String questionSafe = question == null ? "" : question;
        String answerSafe = answer == null ? "" : answer;
        List<String> contextsSafe = contexts == null ? Collections.emptyList() : contexts;
        String contextBlock = String.join("\n---\n", contextsSafe);

        log.info("Evaluating generation quality for question: {}",
                questionSafe.length() > 50 ? questionSafe.substring(0, 50) + "..." : questionSafe);

        GenerationMetrics metrics = ragasEnabled
                ? evaluateWithRagas(questionSafe, answerSafe, contextsSafe, contextBlock, expectedAnswer)
                : evaluateWithLegacy(questionSafe, answerSafe, contextBlock, expectedAnswer);

        log.info("Generation metrics: {}", metrics);
        return metrics;
    }

    // ──────────────────────────────────────────────────────────────
    //  新机制：真实 RAGAS
    // ──────────────────────────────────────────────────────────────

    private GenerationMetrics evaluateWithRagas(String question, String answer,
                                                List<String> contexts, String contextBlock,
                                                String expectedAnswer) {
        GenerationMetrics metrics = new GenerationMetrics();

        List<Claim> faithfulnessClaims = new ArrayList<>();
        List<Boolean> contextRelevance = new ArrayList<>();
        List<Claim> recallClaims = new ArrayList<>();
        String relevancyMethod = "unavailable";

        double faithfulness = UNAVAILABLE;
        double answerRelevancy = UNAVAILABLE;
        double contextPrecision = UNAVAILABLE;
        double contextRecall = UNAVAILABLE;

        // 1. Faithfulness —— 每个指标独立 try-catch，互不影响
        try {
            faithfulness = scoreFaithfulnessRagas(answer, contextBlock, faithfulnessClaims);
        } catch (Exception e) {
            log.warn("Faithfulness 评估失败：{}", e.getMessage());
            faithfulness = UNAVAILABLE;
        }

        // 2. Answer Relevancy
        try {
            RelevancyResult result = scoreAnswerRelevancyRagas(question, answer);
            answerRelevancy = result.score();
            relevancyMethod = result.method();
        } catch (Exception e) {
            log.warn("Answer Relevancy 评估失败：{}", e.getMessage());
            answerRelevancy = UNAVAILABLE;
            relevancyMethod = "unavailable";
        }

        // 3. Context Precision
        try {
            contextPrecision = scoreContextPrecisionRagas(question, contexts, contextRelevance);
        } catch (Exception e) {
            log.warn("Context Precision 评估失败：{}", e.getMessage());
            contextPrecision = UNAVAILABLE;
        }

        // 4. Context Recall（需要 expectedAnswer）
        try {
            if (expectedAnswer != null && !expectedAnswer.isBlank()) {
                contextRecall = scoreContextRecallRagas(expectedAnswer, contextBlock, recallClaims);
            }
        } catch (Exception e) {
            log.warn("Context Recall 评估失败：{}", e.getMessage());
            contextRecall = UNAVAILABLE;
        }

        metrics.setFaithfulness(faithfulness);
        metrics.setAnswerRelevancy(answerRelevancy);
        metrics.setContextPrecision(contextPrecision);
        metrics.setContextRecall(contextRecall);

        // 明细字段（非数值，带 @JsonIgnore，不参与任何数值平均）
        metrics.setFaithfulnessClaims(faithfulnessClaims);
        metrics.setContextRelevance(contextRelevance);
        metrics.setRecallClaims(recallClaims);
        metrics.setAnswerRelevancyMethod(relevancyMethod);
        return metrics;
    }

    /**
     * Faithfulness：decomposeClaims(answer) → 逐条 isSupported(claim, contexts) → 占比。
     */
    private double scoreFaithfulnessRagas(String answer, String contextBlock,
                                          List<Claim> outClaims) {
        if (answer == null || answer.isBlank()) {
            // 空答案没有任何可被证伪的声明，视为无幻觉 → 1.0（不调用 LLM）
            return RagasScorer.faithfulness(Collections.emptyList());
        }

        List<String> claimTexts = llmJudge.decomposeClaims(answer);
        if (!llmJudge.isLastCallSucceeded()) {
            // LLM 分解不可用 → 该指标记 -1.0，绝不猜一个分数
            return UNAVAILABLE;
        }

        List<Claim> claims = new ArrayList<>(claimTexts.size());
        for (String text : claimTexts) {
            Claim claim = new Claim(text);
            claim.setSupported(llmJudge.isSupported(text, contextBlock));
            claims.add(claim);
        }
        // 最后一条声明核验也失败，说明 LLM 中途不可用 → 保守记 -1.0
        if (!claims.isEmpty() && !llmJudge.isLastCallSucceeded()) {
            return UNAVAILABLE;
        }
        outClaims.addAll(claims);
        return RagasScorer.faithfulness(claims);
    }

    /**
     * Answer Relevancy：反向生成问题 → embedding 余弦均值；不可用则回退 Jaccard。
     */
    private RelevancyResult scoreAnswerRelevancyRagas(String question, String answer) {
        if (answer == null || answer.isBlank() || question == null || question.isBlank()) {
            return new RelevancyResult(UNAVAILABLE, "unavailable");
        }

        List<String> generatedQuestions = llmJudge.reverseGenerateQuestions(answer, REVERSED_QUESTION_COUNT);
        if (!llmJudge.isLastCallSucceeded() || generatedQuestions.isEmpty()) {
            return new RelevancyResult(UNAVAILABLE, "unavailable");
        }

        // 优先 embedding：把原问题 + 生成问题一次性批量取向量
        List<String> toEmbed = new ArrayList<>(generatedQuestions.size() + 1);
        toEmbed.add(question);
        toEmbed.addAll(generatedQuestions);

        EmbedResult embed = embedTexts(toEmbed);
        if (embed.remote()) {
            double[] originalVec = embed.vectors().get(0);
            List<double[]> generatedVecs = new ArrayList<>(embed.vectors().subList(1, embed.vectors().size()));
            double score = RagasScorer.answerRelevancy(generatedVecs, originalVec);
            if (score >= 0.0) {
                return new RelevancyResult(score, "embedding");
            }
        }

        // embedding 不可用（本地降级/远程失败/向量异常）→ 本地确定性 Jaccard 回退
        double jaccardScore = RagasScorer.answerRelevancyJaccard(generatedQuestions, question);
        return new RelevancyResult(jaccardScore, "jaccard");
    }

    /**
     * Context Precision：逐个 chunk 判定相关性 → AP@K。
     */
    private double scoreContextPrecisionRagas(String question, List<String> contexts,
                                              List<Boolean> outRelevance) {
        if (contexts == null || contexts.isEmpty()) {
            // 没有检索到任何上下文：相关项为 0 → AP = 0.0（真实计算值，非编造）
            return RagasScorer.averagePrecision(Collections.emptyList());
        }
        List<Boolean> relevance = new ArrayList<>(contexts.size());
        for (String chunk : contexts) {
            relevance.add(llmJudge.isRelevant(question, chunk));
        }
        if (!llmJudge.isLastCallSucceeded()) {
            // 最后一次相关性判定失败 → LLM 不可用，保守记 -1.0
            return UNAVAILABLE;
        }
        outRelevance.addAll(relevance);
        return RagasScorer.averagePrecision(relevance);
    }

    /**
     * Context Recall：decomposeClaims(expectedAnswer) → 用上下文整体判定覆盖 → 占比。
     */
    private double scoreContextRecallRagas(String expectedAnswer, String contextBlock,
                                           List<Claim> outClaims) {
        List<String> claimTexts = llmJudge.decomposeClaims(expectedAnswer);
        if (!llmJudge.isLastCallSucceeded()) {
            return UNAVAILABLE;
        }

        List<Claim> claims = new ArrayList<>(claimTexts.size());
        for (String text : claimTexts) {
            Claim claim = new Claim(text);
            claim.setSupported(llmJudge.isSupported(text, contextBlock));
            claims.add(claim);
        }
        if (!claims.isEmpty() && !llmJudge.isLastCallSucceeded()) {
            return UNAVAILABLE;
        }
        outClaims.addAll(claims);
        return RagasScorer.contextRecall(claims);
    }

    // ──────────────────────────────────────────────────────────────
    //  Embedding：远程 / 本地降级的区分
    // ──────────────────────────────────────────────────────────────

    /**
     * 批量取向量，并判定本次结果是否「远程真正成功」。
     * <p>
     * {@link EmbeddingService} 内部有三级降级（远程 → 本地哈希 → 空向量），
     * 但未直接回传单次调用来源。这里采用<b>计数器差分</b>识别降级：
     * 记录调用前 {@code getStatus().localFallback}，调用后若该计数增加，说明本次
     * <b>走了本地哈希降级</b>（非语义向量），则本指标按「embedding 不可用」处理，回退 Jaccard。
     * <p>
     * 已知局限：该计数器是全局的，若有其他组件并发触发本地降级，可能把本次远程成功误判为降级；
     * 该误判方向偏保守（使用更保守的确定性回退），不会产生编造分数。
     *
     * @param texts 待嵌入文本
     * @return 向量与「是否远程成功」标记
     */
    private EmbedResult embedTexts(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new EmbedResult(Collections.emptyList(), false);
        }
        long fallbackBefore = localFallbackCount();
        List<List<Float>> raw;
        try {
            raw = embeddingService.embedBatch(texts);
        } catch (Exception e) {
            log.warn("Embedding 调用异常：{}", e.getMessage());
            return new EmbedResult(Collections.emptyList(), false);
        }
        long fallbackAfter = localFallbackCount();
        boolean degradedToLocal = fallbackAfter > fallbackBefore;
        boolean remoteConfigured = embeddingService.isRemoteConfigured();

        boolean vectorsValid = raw != null && raw.size() == texts.size();
        List<double[]> vectors = new ArrayList<>();
        int dim = -1;
        if (vectorsValid) {
            for (List<Float> vec : raw) {
                if (vec == null || vec.isEmpty()) {
                    vectorsValid = false;
                    break;
                }
                if (dim == -1) {
                    dim = vec.size();
                } else if (vec.size() != dim) {
                    vectorsValid = false;
                    break;
                }
                double[] dense = new double[vec.size()];
                for (int i = 0; i < vec.size(); i++) {
                    dense[i] = vec.get(i);
                }
                vectors.add(dense);
            }
        }

        boolean remote = remoteConfigured && !degradedToLocal && vectorsValid;
        if (!remote) {
            // 非远程成功时调用方会走 Jaccard 回退，此处不返回半成品向量
            return new EmbedResult(Collections.emptyList(), false);
        }
        return new EmbedResult(vectors, true);
    }

    private long localFallbackCount() {
        try {
            Object value = embeddingService.getStatus().get("localFallback");
            return value instanceof Number ? ((Number) value).longValue() : 0L;
        } catch (Exception e) {
            log.debug("读取 EmbeddingService 状态失败：{}", e.getMessage());
            return 0L;
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  旧机制（evaluation.ragas.enabled=false 时的可选退路）
    //  单 prompt 直接让 LLM 报一个 0~1 之间的数字。
    // ──────────────────────────────────────────────────────────────

    private GenerationMetrics evaluateWithLegacy(String question, String answer,
                                                 String contextBlock, String expectedAnswer) {
        GenerationMetrics metrics = new GenerationMetrics();
        metrics.setFaithfulness(scoreFaithfulnessLegacy(answer, contextBlock));
        metrics.setAnswerRelevancy(scoreAnswerRelevancyLegacy(question, answer));
        metrics.setContextPrecision(scoreContextPrecisionLegacy(question, contextBlock));

        double contextRecall = UNAVAILABLE;
        if (expectedAnswer != null && !expectedAnswer.isBlank()) {
            contextRecall = scoreContextRecallLegacy(expectedAnswer, contextBlock);
        }
        metrics.setContextRecall(contextRecall);
        metrics.setAnswerRelevancyMethod("legacy-single-prompt");
        return metrics;
    }

    /**
     * Faithfulness 评分（旧路径）：单 prompt 让 LLM 报一个数字。
     */
    private double scoreFaithfulnessLegacy(String answer, String context) {
        String prompt = String.format("""
                请评估以下答案对给定上下文的忠实度。

                上下文:
                %s

                答案:
                %s

                评估标准:
                - 提取答案中的所有关键事实声明
                - 检查每个声明是否有上下文支持
                - 计算被支持的声明占总声明数的比例

                请仅返回一个 0 到 1 之间的数字（例如 0.85），不要返回其他内容。
                """, truncate(context, 3000), truncate(answer, 1000));

        return callLLMForScore(prompt);
    }

    /**
     * Answer Relevancy 评分（旧路径）。
     */
    private double scoreAnswerRelevancyLegacy(String question, String answer) {
        String prompt = String.format("""
                请评估以下答案与问题的相关性。

                问题:
                %s

                答案:
                %s

                评估标准:
                - 答案是否直接回答了问题
                - 答案中是否有无关内容
                - 答案是否完整地回应了问题的各个方面

                请仅返回一个 0 到 1 之间的数字（例如 0.90），不要返回其他内容。
                """, truncate(question, 500), truncate(answer, 1000));

        return callLLMForScore(prompt);
    }

    /**
     * Context Precision 评分（旧路径）。
     */
    private double scoreContextPrecisionLegacy(String question, String context) {
        String prompt = String.format("""
                请评估以下上下文与问题的精确度（即上下文中有多少内容是与问题相关的）。

                问题:
                %s

                上下文:
                %s

                评估标准:
                - 上下文中与问题直接相关的内容比例
                - 无关内容的比例

                请仅返回一个 0 到 1 之间的数字（例如 0.75），不要返回其他内容。
                """, truncate(question, 500), truncate(context, 3000));

        return callLLMForScore(prompt);
    }

    /**
     * Context Recall 评分（旧路径）。
     */
    private double scoreContextRecallLegacy(String expectedAnswer, String context) {
        String prompt = String.format("""
                请评估以下上下文对期望答案的召回率（即期望答案中的关键信息有多少被上下文覆盖）。

                期望答案:
                %s

                上下文:
                %s

                评估标准:
                - 期望答案中的关键事实/信息点
                - 这些信息点有多少被上下文覆盖

                请仅返回一个 0 到 1 之间的数字（例如 0.80），不要返回其他内容。
                """, truncate(expectedAnswer, 1000), truncate(context, 3000));

        return callLLMForScore(prompt);
    }

    /**
     * 调用 LLM 获取分数（旧路径）。
     */
    private double callLLMForScore(String prompt) {
        try {
            Map<String, Object> requestBody = Map.of(
                    "model", openAIConfig.getModel(),
                    "messages", List.of(
                            Map.of("role", "system", "content", "你是一个严格的质量评估专家。只输出一个 0 到 1 之间的数字。"),
                            Map.of("role", "user", "content", prompt)
                    ),
                    "temperature", 0.0,
                    "max_tokens", 10
            );

            String response = openAiWebClient.post()
                    .uri("/chat/completions")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            return parseScore(response);
        } catch (Exception e) {
            log.error("Failed to call LLM for scoring: {}", e.getMessage(), e);
            return UNAVAILABLE;
        }
    }

    /**
     * 从 LLM 响应中解析分数（旧路径）。
     */
    private double parseScore(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            String content = root.at("/choices/0/message/content").asText("").trim();

            try {
                double score = Double.parseDouble(content);
                return Math.max(0.0, Math.min(1.0, score));
            } catch (NumberFormatException ignored) {
            }

            String numericPart = content.replaceAll("[^0-9.]", "");
            if (!numericPart.isEmpty()) {
                double score = Double.parseDouble(numericPart);
                return Math.max(0.0, Math.min(1.0, score));
            }

            log.warn("Could not parse score from LLM response: {}", content);
            return UNAVAILABLE;
        } catch (Exception e) {
            log.error("Failed to parse LLM response: {}", e.getMessage());
            return UNAVAILABLE;
        }
    }

    /**
     * 截断文本到指定长度。
     */
    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }

    // ──────────────────────────────────────────────────────────────
    //  内部辅助类型
    // ──────────────────────────────────────────────────────────────

    /**
     * Answer Relevancy 结果：分数 + 实际使用的方法（embedding / jaccard / unavailable）。
     */
    private record RelevancyResult(double score, String method) {
    }

    /**
     * 一次批量嵌入的结果：向量列表 + 是否为「远程真正成功」。
     */
    private record EmbedResult(List<double[]> vectors, boolean remote) {
    }

    // ──────────────────────────────────────────────────────────────
    //  结果类
    // ──────────────────────────────────────────────────────────────

    /**
     * 生成指标结果。
     * <p>
     * 前四个为「数值主字段」，是评测平均与对比的依据；
     * 其后的「明细字段」为诊断用途，均带 {@code @JsonIgnore}，不会进入任何数值平均。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GenerationMetrics {

        /** 忠实度：答案是否忠于上下文 */
        private double faithfulness;

        /** 答案相关性：答案是否与问题相关 */
        private double answerRelevancy;

        /** 上下文精确度：检索上下文中有多少是相关的（AP@K） */
        private double contextPrecision;

        /** 上下文召回率：期望答案信息是否被上下文覆盖，-1 表示未评估 */
        private double contextRecall;

        /** 明细：Faithfulness 分解出的 claim 及逐条核验结果（不参与数值平均）。 */
        @JsonIgnore
        private List<Claim> faithfulnessClaims;

        /** 明细：Context Precision 中按检索排名给出的相关性判定（不参与数值平均）。 */
        @JsonIgnore
        private List<Boolean> contextRelevance;

        /** 明细：Context Recall 分解出的 golden claim 及覆盖结果（不参与数值平均）。 */
        @JsonIgnore
        private List<Claim> recallClaims;

        /** 明细：Answer Relevancy 实际使用的方法（embedding / jaccard / unavailable）。 */
        @JsonIgnore
        private String answerRelevancyMethod;

        @Override
        public String toString() {
            return String.format(
                    "Faithfulness=%.4f, AnswerRelevancy=%.4f, ContextPrecision=%.4f, ContextRecall=%.4f",
                    faithfulness, answerRelevancy, contextPrecision,
                    contextRecall < 0 ? Double.NaN : contextRecall);
        }
    }
}
