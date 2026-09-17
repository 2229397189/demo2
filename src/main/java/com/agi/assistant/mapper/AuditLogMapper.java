package com.agi.assistant.mapper;

import com.agi.assistant.model.entity.AuditLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;

public interface AuditLogMapper extends BaseMapper<AuditLog> {

    /**
     * 幂等插入审计日志。
     * <p>
     * 使用 {@code INSERT IGNORE}：当 {@code event_id} 命中唯一索引
     * {@code uk_audit_event_id} 时，重复行被静默忽略且<b>不覆盖</b>已有记录。
     * 这是「DB 主写 + Kafka 旁路」不产生重复行的关键：Kafka 消费者收到
     * 生产者已落库的同一条消息时，本次插入返回 0。
     * <p>
     * 刻意不使用 {@code ON DUPLICATE KEY UPDATE}：审计记录需要「原生、不被覆写」，
     * 重复时宁可丢弃也不改写既有证据。
     *
     * @param auditLog 待写入的审计记录（{@code eventId} 为幂等键）
     * @return 真正写入的行数：1 表示新写入，0 表示命中唯一键被忽略
     */
    @Insert("INSERT IGNORE INTO audit_log "
            + "(event_id, user_id, action, resource, risk_level, blocked, details, ip_address, user_agent, created_at) "
            + "VALUES (#{eventId}, #{userId}, #{action}, #{resource}, #{riskLevel}, #{blocked}, "
            + "#{details}, #{ipAddress}, #{userAgent}, #{createdAt})")
    int insertIgnore(AuditLog auditLog);
}
