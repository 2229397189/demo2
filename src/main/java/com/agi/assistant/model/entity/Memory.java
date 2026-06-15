package com.agi.assistant.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("memory")
public class Memory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String content;

    private String type;

    private Double importance;

    private Integer accessCount;

    private LocalDateTime lastAccessedAt;

    private String embeddingId;

    private String metadata;

    private LocalDateTime expiresAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
