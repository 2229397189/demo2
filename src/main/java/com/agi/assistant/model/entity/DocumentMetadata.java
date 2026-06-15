package com.agi.assistant.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文档元数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentMetadata {

    /**
     * 文档标题
     */
    private String title;

    /**
     * 作者
     */
    private String author;

    /**
     * 发布日期
     */
    private LocalDateTime publishDate;

    /**
     * 标签列表
     */
    private List<String> tags;

    /**
     * 文档来源 URL
     */
    private String sourceUrl;

    /**
     * 文档语言
     */
    private String language;

    /**
     * 文档摘要
     */
    private String summary;
}
