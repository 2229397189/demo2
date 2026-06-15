package com.agi.assistant.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 知识图谱中的关系边
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphRelation {

    /**
     * 关系唯一标识
     */
    private String relationId;

    /**
     * 关系类型
     */
    private String type;

    /**
     * 起始实体名称
     */
    private String startEntity;

    /**
     * 目标实体名称
     */
    private String endEntity;

    /**
     * 关系属性
     */
    private Map<String, Object> properties;

    /**
     * 关系权重/分数
     */
    private double score;
}
