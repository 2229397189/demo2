package com.agi.assistant.service.security;

import com.agi.assistant.config.KafkaConfig;
import com.agi.assistant.mapper.AuditLogMapper;
import com.agi.assistant.model.entity.AuditLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 审计 Kafka 消费者 —— 补齐审计通道的消费端，使其真正闭环。
 * <p>
 * 背景：{@link AuditService} 采用「DB 主写 + Kafka 旁路」，此前全项目没有任何
 * {@code @KafkaListener}，审计消息发出后无人消费，是一条死链。本类把消费端接上。
 * <p>
 * <b>幂等语义</b>：{@code AuditService} 落库时已经写过一次 DB，本消费者再从 Kafka
 * 收到同一条消息，会再次尝试落库。为避免重复行，靠 {@code audit_log.event_id}
 * 上的唯一索引 {@code uk_audit_event_id} + {@code INSERT IGNORE}：
 * 插入返回 0 即表示「重复消息，已忽略」，这是<b>正常路径</b>，不记 error。
 * <p>
 * <b>旁路定位</b>：消费者不在业务主流程上，其任何失败都不得影响业务，也不应因抛异常
 * 触发消费者容器无限重试刷屏 —— 因此所有异常在方法内部消化为 {@code warn} 级日志。
 * <p>
 * <b>装配门控</b>：与 {@link KafkaConfig} 保持一致的 {@code spring.kafka.enabled=true}，
 * 并叠加 {@code audit.kafka-consumer.enabled}（见 application.yml）。
 * 两者任一为 false（或 Kafka 组件缺失）时本 Bean 不装配，应用照常启动 ——
 * 保证「Kafka 缺失时不崩」。Docker / CI 等无 Kafka 环境只需把开关置 false 即可。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "spring.kafka.enabled", havingValue = "true", matchIfMissing = false)
@ConditionalOnExpression("${audit.kafka-consumer.enabled:true}")
public class AuditKafkaConsumer {

    private final AuditLogMapper auditLogMapper;

    public AuditKafkaConsumer(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * 消费一条审计消息并幂等落库。
     * <p>
     * 入参类型与 {@link KafkaConfig} 中配置的反序列化器一致：消费者
     * {@code VALUE_DESERIALIZER_CLASS_CONFIG} 为 {@code JsonDeserializer}，且
     * {@code VALUE_DEFAULT_TYPE} 指向 {@code com.agi.assistant.model.entity.AuditLog}，
     * 因此 Spring Kafka 会把消息体直接反序列化为 {@link AuditLog} 对象。
     *
     * @param auditLog 从 Kafka 反序列化得到的审计记录（可能为 null，做防御）
     */
    @KafkaListener(
            topics = KafkaConfig.TOPIC_AUDIT_LOG,
            groupId = "${spring.kafka.consumer.group-id:agi-assistant-audit}")
    public void onMessage(AuditLog auditLog) {
        if (auditLog == null) {
            log.warn("[AUDIT-CONSUMER] 收到空审计消息，跳过");
            return;
        }

        try {
            int affected = auditLogMapper.insertIgnore(auditLog);
            if (affected > 0) {
                log.debug("[AUDIT-CONSUMER] 审计消息落库: eventId={}, action={}, resource={}",
                        auditLog.getEventId(), auditLog.getAction(), auditLog.getResource());
            } else {
                // 命中唯一索引 event_id → 生产者已落库，本次为重复消息，属正常幂等路径
                log.debug("[AUDIT-CONSUMER] 重复审计消息已忽略（幂等）: eventId={}, action={}",
                        auditLog.getEventId(), auditLog.getAction());
            }
        } catch (Exception e) {
            // 消费者是旁路：失败只告警，不抛出（避免容器无限重试刷屏，也不影响业务主流程）。
            log.warn("[AUDIT-CONSUMER] 审计消息落库失败（旁路，不影响业务）: eventId={}, action={}, err={}",
                    auditLog.getEventId(), auditLog.getAction(), e.getMessage());
        }
    }
}
