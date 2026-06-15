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

    private String tags;

    private String source;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
