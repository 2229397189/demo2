package com.agi.assistant.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 解析后的文档实体，包含清理后的正文内容和元数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParsedDocument {

    /**
     * 文档唯一标识
     */
    private String documentId;

    /**
     * 清洗后的正文内容
     */
    private String cleanedContent;

    /**
     * 原始 Markdown 内容
     */
    private String rawContent;

    /**
     * 文档元数据
     */
    private DocumentMetadata metadata;
}
