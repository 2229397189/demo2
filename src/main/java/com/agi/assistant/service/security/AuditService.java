package com.agi.assistant.service.security;

import com.agi.assistant.model.entity.AuditLog;
import com.agi.assistant.model.enums.ToolRiskLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 审计服务
 * <p>
 * 通过 Kafka 异步发送审计日志，保证高吞吐量和可靠性。
 * Kafka 不可用时降级为日志记录。
 */
@Slf4j
@Lazy
@Service
public class AuditService {

    @Autowired(required = false)
    private KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * 记录审计日志。
     * <p>
     * 异步发送到 Kafka topic，失败时记录错误日志但不阻断业务。
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
        auditLog.setUserId(userId);
        auditLog.setAction(action);
        auditLog.setResource(resource);
        auditLog.setRiskLevel(riskLevel != null ? riskLevel.name() : ToolRiskLevel.SAFE.name());
        auditLog.setBlocked(blocked ? 1 : 0);
        auditLog.setDetails(details);
        auditLog.setCreatedAt(LocalDateTime.now());

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
     * 将审计日志发送到 Kafka，Kafka 不可用时降级为日志记录。
     */
    private void sendToKafka(AuditLog auditLog) {
        if (kafkaTemplate == null) {
            log.info("[AUDIT LOG] (Kafka unavailable) userId={}, action={}, resource={}, risk={}, blocked={}, time={}",
                    auditLog.getUserId(), auditLog.getAction(), auditLog.getResource(),
                    auditLog.getRiskLevel(), auditLog.getBlocked(), auditLog.getCreatedAt());
            return;
        }

        try {
            String key = auditLog.getUserId() != null
                    ? String.valueOf(auditLog.getUserId())
                    : "anonymous";

            kafkaTemplate.send("agi-audit-log", key, auditLog)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to send audit log to Kafka: {}", ex.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.error("Error sending audit log to Kafka: {}", e.getMessage());
        }
    }
}
