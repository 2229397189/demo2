package com.agi.assistant.service.evaluation;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.agi.assistant.config.OpenAIConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * 生成评估器
 * <p>
 * 基于 RAGAS 框架评估生成质量，使用 LLM 评分以下指标：
 * <ul>
 *   <li>Faithfulness（忠实度）：答案是否忠于检索到的上下文</li>
 *   <li>Answer Relevancy（答案相关性）：答案是否与问题相关</li>
 *   <li>Context Precision（上下文精确度）：检索上下文中有多少是相关的</li>
 *   <li>Context Recall（上下文召回率）：期望答案中的信息是否被上下文覆盖</li>
 * </ul>
 */
@Slf4j
@Service
public class GenerationEvaluator {

    private final WebClient openAiWebClient;
    private final OpenAIConfig openAIConfig;
    private final ObjectMapper objectMapper;

    public GenerationEvaluator(WebClient openAiWebClient,
                                OpenAIConfig openAIConfig,
                                ObjectMapper objectMapper) {
        this.openAiWebClient = openAiWebClient;
        this.openAIConfig = openAIConfig;
        this.objectMapper = objectMapper;
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
        log.info("Evaluating generation quality for question: {}",
                question.length() > 50 ? question.substring(0, 50) + "..." : question);

        String contextBlock = String.join("\n---\n", contexts);

        // 1. Faithfulness
        double faithfulness = scoreFaithfulness(answer, contextBlock);

        // 2. Answer Relevancy
        double answerRelevancy = scoreAnswerRelevancy(question, answer);

        // 3. Context Precision
        double contextPrecision = scoreContextPrecision(question, contextBlock);

        // 4. Context Recall（需要 expectedAnswer）
        double contextRecall = -1.0;
        if (expectedAnswer != null && !expectedAnswer.isBlank()) {
            contextRecall = scoreContextRecall(expectedAnswer, contextBlock);
        }

        GenerationMetrics metrics = new GenerationMetrics(
                faithfulness, answerRelevancy, contextPrecision, contextRecall);

        log.info("Generation metrics: {}", metrics);
        return metrics;
    }

    /**
     * Faithfulness 评分：答案是否忠于上下文。
     * <p>
     * 提取答案中的关键声明，检查每个声明是否被上下文支持。
     */
    private double scoreFaithfulness(String answer, String context) {
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
     * Answer Relevancy 评分：答案是否与问题相关。
     */
    private double scoreAnswerRelevancy(String question, String answer) {
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
     * Context Precision 评分：检索上下文中有多少与问题相关。
     */
    private double scoreContextPrecision(String question, String context) {
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
     * Context Recall 评分：期望答案中的信息是否被上下文覆盖。
     */
    private double scoreContextRecall(String expectedAnswer, String context) {
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
     * 调用 LLM 获取分数。
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
            return -1.0;
        }
    }

    /**
     * 从 LLM 响应中解析分数。
     */
    private double parseScore(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            String content = root.at("/choices/0/message/content").asText("").trim();

            // 尝试直接解析数字
            try {
                double score = Double.parseDouble(content);
                return Math.max(0.0, Math.min(1.0, score));
            } catch (NumberFormatException ignored) {
            }

            // 尝试提取数字部分
            String numericPart = content.replaceAll("[^0-9.]", "");
            if (!numericPart.isEmpty()) {
                double score = Double.parseDouble(numericPart);
                return Math.max(0.0, Math.min(1.0, score));
            }

            log.warn("Could not parse score from LLM response: {}", content);
            return -1.0;
        } catch (Exception e) {
            log.error("Failed to parse LLM response: {}", e.getMessage());
            return -1.0;
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
    //  结果类
    // ──────────────────────────────────────────────────────────────

    /**
     * 生成指标结果
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GenerationMetrics {

        /** 忠实度：答案是否忠于上下文 */
        private double faithfulness;

        /** 答案相关性：答案是否与问题相关 */
        private double answerRelevancy;

        /** 上下文精确度：检索上下文中有多少是相关的 */
        private double contextPrecision;

        /** 上下文召回率：期望答案信息是否被上下文覆盖，-1 表示未评估 */
        private double contextRecall;

        @Override
        public String toString() {
            return String.format(
                    "Faithfulness=%.4f, AnswerRelevancy=%.4f, ContextPrecision=%.4f, ContextRecall=%.4f",
                    faithfulness, answerRelevancy, contextPrecision,
                    contextRecall < 0 ? Double.NaN : contextRecall);
        }
    }
}
