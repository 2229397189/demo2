package com.agi.assistant.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("evaluation_result")
public class EvaluationResult {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    private Long queryId;

    private String query;

    private String generatedAnswer;

    private String expectedAnswer;

    /**
     * JSON格式的检索文档ID列表
     */
    private String retrievedDocIds;

    /**
     * JSON格式的检索指标
     */
    private String retrievalMetrics;

    /**
     * JSON格式的生成指标
     */
    private String generationMetrics;

    private Long latencyMs;

    private LocalDateTime createdAt;
}
