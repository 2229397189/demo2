package com.agi.assistant.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 评测结果快照 DTO（结构确定性）。
 * <p>
 * 由 {@code EvaluationSnapshotService} 导出为 json + md。字段顺序固定为
 * {@code meta/retrieval/generation/perQuery}；{@code perQuery} 按 {@code queryId} 升序稳定排列。
 * <p>
 * 诚信约定（红线 C3）：所有指标数值用包装类型 {@link Double}，**未评估一律输出 null**
 * 并令对应 {@code evaluated=false}，绝不写死占位数字。
 *
 * @author Alex
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationSnapshot {

    /** 元信息。 */
    private Meta meta;

    /** 检索指标汇总。 */
    private RetrievalSummary retrieval;

    /** 生成指标汇总。 */
    private GenerationSummary generation;

    /** 逐 query 明细（按 queryId 升序）。 */
    private List<QueryRecord> perQuery;

    /**
     * 快照元信息。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Meta {

        /** 数据集标识。 */
        private String datasetId;

        /** 检索策略（dense/sparse/graph/hybrid/full）。 */
        private String strategy;

        /** 使用的模型标识。 */
        private String model;

        /** 生成时间（快照内唯一时间戳字段）。 */
        private String generatedAt;

        /** 评测任务 ID。 */
        private Long taskId;

        /** query 数量。 */
        private int queryCount;
    }

    /**
     * 检索指标汇总。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RetrievalSummary {

        /** Recall@K。 */
        private Double recallAtK;

        /** Precision@K。 */
        private Double precisionAtK;

        /** MRR。 */
        private Double mrr;

        /** NDCG@K。 */
        private Double ndcgAtK;

        /** HitRate。 */
        private Double hitRate;

        /** 是否已完成评估（false 时上述数值应为 null）。 */
        private boolean evaluated;
    }

    /**
     * 生成指标汇总。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GenerationSummary {

        /** Faithfulness。 */
        private Double faithfulness;

        /** Answer Relevancy。 */
        private Double answerRelevancy;

        /** Context Precision。 */
        private Double contextPrecision;

        /** Context Recall。 */
        private Double contextRecall;

        /** 是否已完成评估（false 时上述数值应为 null）。 */
        private boolean evaluated;
    }

    /**
     * 单条 query 的评测记录。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QueryRecord {

        /** query ID。 */
        private Long queryId;

        /** 查询文本。 */
        private String query;

        /** 生成的答案。 */
        private String generatedAnswer;

        /** 端到端延迟（毫秒）。 */
        private Double latencyMs;

        /** 该 query 的检索指标。 */
        private RetrievalSummary retrieval;

        /** 该 query 的生成指标。 */
        private GenerationSummary generation;
    }
}
