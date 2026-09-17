package com.agi.assistant.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("document")
public class Document {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String title;

    private String filePath;

    private String fileType;

    private Long fileSize;

    private Integer chunkCount;

    private Integer status;

    /**
     * 最近一次处理的错误 / 降级说明。
     * <p>
     * 成功时为空串；PARTIAL / FAILED 时记录具体失败路径（如「向量索引失败: ...」），
     * 便于前端定位「为什么这篇文档检索不到」。字段由 sql/init.sql 的幂等迁移补齐。
     */
    private String errorMessage;

    private String tags;

    private String source;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
