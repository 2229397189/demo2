package com.agi.assistant.service.security;

import com.agi.assistant.mapper.AuditLogMapper;
import com.agi.assistant.model.entity.AuditLog;
import com.agi.assistant.model.enums.ToolRiskLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 审计服务
 * <p>
 * 审计日志有两条落地路径：
 * <ol>
 *   <li><b>数据库</b>（主路径）：直接写 {@code audit_log} 表。审计的本质要求是「可追溯」，
 *       而 Kafka 是可选组件（{@code spring.kafka.enabled} 默认 false），
 *       把审计完全建立在可选组件上，等于默认没有审计。</li>
 *   <li><b>Kafka</b>（旁路）：开启后额外投递一份，供下游实时消费 / 告警。</li>
 * </ol>
 * <p>
 * 修复说明：此前本类只发 Kafka，Kafka 不可用时仅打一条 log 就结束 ——
 * {@code AuditLogMapper} 与 {@code audit_log} 表从头到尾没人用过，
 * 属于「看起来有审计、实际全丢了」。现在写库为主、Kafka 为辅，两者
 * 任一失败都不阻断业务，但会在日志里明确记下来。
 * <p>
 * 安全红线：审计写入绝不能因为自身失败而影响业务，所以所有异常都在本类内部消化。
 */
@Slf4j
@Lazy
@Service
public class AuditService {

    public static final String KAFKA_TOPIC_AUDIT_LOG = "agi-audit-log";

    /** 单条 details 落库长度上限，防止超长内容把行撑爆（列宽 1024/2048 级别） */
    private static final int MAX_DETAILS_LENGTH = 2000;

    private final AuditLogMapper auditLogMapper;

    @Autowired(required = false)
    private KafkaTemplate<String, Object> kafkaTemplate;

    public AuditService(@Autowired(required = false) AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * 记录审计日志。
     *
     * @param userId    用户 ID
     * @param action    操作类型
     * @param resource  操作资源
     * @param riskLevel 风险等级
     * @param blocked   是否被阻断
     * @param details   详细信息
     */
    public void log(Long userId, String action, String resource,
                    ToolRiskLevel riskLevel, boolean blocked, String details) {

        AuditLog auditLog = new AuditLog();
        // 幂等键：同一值同时进入 DB 记录与 Kafka 消息体（列宽 64，去横线 UUID 为 32 位），
        // 消费者据此走 INSERT IGNORE 去重。
        auditLog.setEventId(newEventId());
        auditLog.setUserId(userId);
        auditLog.setAction(action);
        auditLog.setResource(resource);
        auditLog.setRiskLevel(riskLevel != null ? riskLevel.name() : ToolRiskLevel.SAFE.name());
        auditLog.setBlocked(blocked ? 1 : 0);
        auditLog.setDetails(truncateDetails(details));
        auditLog.setCreatedAt(LocalDateTime.now());

        persist(auditLog);
        sendToKafka(auditLog);
    }

    /**
     * 记录审计日志（简化接口）。
     *
     * @param userId   用户 ID
     * @param action   操作类型
     * @param resource 操作资源
     */
    public void log(Long userId, String action, String resource) {
        log(userId, action, resource, ToolRiskLevel.SAFE, false, null);
    }

    /**
     * 记录安全事件。
     *
     * @param userId    用户 ID
     * @param action    操作类型
     * @param resource  操作资源
     * @param riskLevel 风险等级
     * @param blocked   是否被阻断
     */
    public void logSecurityEvent(Long userId, String action, String resource,
                                 ToolRiskLevel riskLevel, boolean blocked) {
        log(userId, action, resource, riskLevel, blocked, "Security event detected");
    }

    /**
     * 写入数据库。失败时降级为日志，不抛异常。
     */
    private void persist(AuditLog auditLog) {
        if (auditLogMapper == null) {
            log.warn("[AUDIT] 审计表不可用（AuditLogMapper 未注入），本条审计仅记录在日志中: "
                    + "userId={}, action={}, resource={}", auditLog.getUserId(),
                    auditLog.getAction(), auditLog.getResource());
            return;
        }

        try {
            auditLogMapper.insert(auditLog);
        } catch (Exception e) {
            log.error("[AUDIT] 审计日志写库失败（不影响业务）: userId={}, action={}, err={}",
                    auditLog.getUserId(), auditLog.getAction(), e.getMessage());
        }
    }

    /**
     * 将审计日志发送到 Kafka（旁路）。Kafka 未启用/不可用时仅记录日志。
     */
    private void sendToKafka(AuditLog auditLog) {
        if (kafkaTemplate == null) {
            log.debug("[AUDIT LOG] (Kafka disabled) userId={}, action={}, resource={}, risk={}, blocked={}",
                    auditLog.getUserId(), auditLog.getAction(), auditLog.getResource(),
                    auditLog.getRiskLevel(), auditLog.getBlocked());
            return;
        }

        try {
            String key = auditLog.getUserId() != null
                    ? String.valueOf(auditLog.getUserId())
                    : "anonymous";

            kafkaTemplate.send(KAFKA_TOPIC_AUDIT_LOG, key, auditLog)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to send audit log to Kafka: {}", ex.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.error("Error sending audit log to Kafka: {}", e.getMessage());
        }
    }

    /**
     * 生成审计事件唯一标识。
     * <p>
     * 使用去横线的 UUID（32 位十六进制），满足 {@code audit_log.event_id VARCHAR(64)}
     * 的长度约束，且每次调用互不相同（区别于固定值 / 时间戳可能碰撞的方案）。
     *
     * @return 32 位事件 ID
     */
    private String newEventId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String truncateDetails(String details) {
        if (details == null) {
            return null;
        }
        return details.length() <= MAX_DETAILS_LENGTH
                ? details
                : details.substring(0, MAX_DETAILS_LENGTH) + "... [truncated]";
    }
}
