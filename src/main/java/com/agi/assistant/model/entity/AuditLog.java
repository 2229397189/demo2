package com.agi.assistant.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 审计事件唯一标识（UUID，32 位）。
     * <p>
     * 生产者（AuditService）在落库前生成，并同时写入 DB 记录与发往 Kafka 的消息体，
     * 两边使用同一个值。消费者（AuditKafkaConsumer）走 {@code INSERT IGNORE}，
     * 依靠 {@code audit_log} 上的唯一索引 {@code uk_audit_event_id} 保证
     * 「DB 主写 + Kafka 旁路」不会产生重复行。长度必须 ≤ 64（列定义 VARCHAR(64)）。
     */
    private String eventId;

    private Long userId;

    private String action;

    private String resource;

    private String riskLevel;

    private Integer blocked;

    private String details;

    private String ipAddress;

    private String userAgent;

    private LocalDateTime createdAt;
}
