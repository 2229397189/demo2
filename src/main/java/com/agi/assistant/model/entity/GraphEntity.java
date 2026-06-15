package com.agi.assistant.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 知识图谱中的实体节点
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphEntity {

    /**
     * 实体唯一标识（Neo4j 节点 ID）
     */
    private String entityId;

    /**
     * 实体名称
     */
    private String name;

    /**
     * 实体类型（如 Person, Concept, Tool 等）
     */
    private String type;

    /**
     * 实体属性
     */
    private Map<String, Object> properties;

    /**
     * 与查询的相关性分数
     */
    private double score;
}
