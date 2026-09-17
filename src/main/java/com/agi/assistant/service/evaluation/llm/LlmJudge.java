package com.agi.assistant.service.evaluation.llm;

import java.util.List;

/**
 * LLM 判定抽象。
 * <p>
 * 把 RAGAS 需要的「可验证子判断」抽象成接口：
 * <ul>
 *   <li>{@link #complete(String, String)} —— 通用补全</li>
 *   <li>{@link #decomposeClaims(String)} —— 把文本拆成原子事实声明</li>
 *   <li>{@link #isSupported(String, String)} —— 逐条 claim 核验</li>
 *   <li>{@link #isRelevant(String, String)} —— chunk 与原问题的相关性</li>
 *   <li>{@link #reverseGenerateQuestions(String, int)} —— 从答案反向生成问题</li>
 * </ul>
 * 抽出接口后，单测可注入 stub 实现，从而在无网络环境下确定性验证聚合数学。
 *
 * @author Alex
 */
public interface LlmJudge {

    /**
     * 通用补全。
     *
     * @param systemPrompt 系统提示
     * @param userPrompt   用户提示
     * @return LLM 文本输出
     */
    String complete(String systemPrompt, String userPrompt);

    /**
     * 把一段文本分解为原子事实声明列表。
     *
     * @param text 待分解文本
     * @return claim 文本列表，绝不返回 null（无 claim 时返回空列表）
     */
    List<String> decomposeClaims(String text);

    /**
     * 判断某条 claim 是否被给定上下文支持。
     *
     * @param claim   原子事实声明
     * @param context 上下文（可含多个 chunk）
     * @return true 表示被支持
     */
    boolean isSupported(String claim, String context);

    /**
     * 判断某 chunk 是否与问题相关。
     *
     * @param question 原问题
     * @param chunk    检索到的片段
     * @return true 表示相关
     */
    boolean isRelevant(String question, String chunk);

    /**
     * 依据答案反向生成「该答案能回答的问题」。
     *
     * @param answer 生成的答案
     * @param n      期望生成的问题数量
     * @return 生成的问题列表，绝不返回 null
     */
    List<String> reverseGenerateQuestions(String answer, int n);
}
