package com.agi.assistant.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 检索结果实体，用于统一表示向量检索、关键词检索、图检索的结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResult {

    /**
     * 文档唯一标识
     */
    private String documentId;

    /**
     * 文档块在原文档中的索引
     */
    private int chunkIndex;

    /**
     * 文档块文本内容
     */
    private String content;

    /**
     * 相关性分数（由检索引擎赋予）
     */
    private double score;

    /**
     * 检索来源：dense / sparse / graph
     */
    private String source;

    /**
     * 文档块所属文档标题
     */
    private String title;

    /**
     * 附加元数据
     */
    private Map<String, Object> metadata;
}
