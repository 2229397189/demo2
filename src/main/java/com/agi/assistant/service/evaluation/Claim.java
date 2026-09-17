package com.agi.assistant.service.evaluation;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RAGAS 中的「原子事实声明」（claim）。
 * <p>
 * 一条答案/参考答案会被 LLM 分解为若干 claim，再逐条核验是否被检索上下文支持，
 * 聚合出 {@code faithfulness} / {@code contextRecall} 等指标。
 *
 * @author Alex
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Claim {

    /** 原子事实声明文本。 */
    private String text;

    /** 是否被上下文 / golden 覆盖（核验结果）。 */
    private boolean supported;

    /** 判定理由（可选）。 */
    private String reason;

    /**
     * 仅携带文本的便捷构造器，默认视为未核验。
     *
     * @param text 声明文本
     */
    public Claim(String text) {
        this.text = text;
        this.supported = false;
        this.reason = null;
    }
}
