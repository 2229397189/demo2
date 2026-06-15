package com.agi.assistant.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("evaluation_task")
public class EvaluationTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String name;

    private Long datasetId;

    private String retrievalStrategy;

    private String modelId;

    private Integer status;

    private Integer totalQueries;

    private Integer completedQueries;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
