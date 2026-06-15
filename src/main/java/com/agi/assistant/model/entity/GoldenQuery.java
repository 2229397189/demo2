package com.agi.assistant.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("golden_query")
public class GoldenQuery {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String datasetId;

    private String query;

    private String expectedAnswer;

    /**
     * JSON格式的相关文档ID列表
     */
    private String relevantDocIds;

    private String difficulty;

    private String category;

    private LocalDateTime createdAt;
}
