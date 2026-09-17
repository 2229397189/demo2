package com.agi.assistant.service.security;

import com.agi.assistant.mapper.AuditLogMapper;
import com.agi.assistant.model.entity.AuditLog;
import com.agi.assistant.model.enums.ToolRiskLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AuditService} 测试。
 * <p>
 * 修复的核心是「审计的主路径必须是数据库，而不是可选的 Kafka」：
 * 此前审计只发 Kafka，Kafka 未启用时仅打一条 log 就结束，
 * {@code AuditLogMapper} 与 {@code audit_log} 表从头到尾没人用过 ——
 * 属于「看起来有审计、实际全丢了」。因此这里首先验证「真的写库了」。
 */
class AuditServiceTest {

    private AuditLogMapper mapper;
    private AuditService auditService;

    private AuditService newService() {
        mapper = mock(AuditLogMapper.class);
        auditService = new AuditService(mapper);
        return auditService;
    }

    @Test
    @DisplayName("审计落到数据库表（主路径），字段完整")
    void auditIsPersistedToDatabase() {
        AuditService service = newService();

        service.log(7L, "TOOL_EXECUTE", "knowledge_search", ToolRiskLevel.WARN, true, "blocked by policy");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(mapper).insert(captor.capture());

        AuditLog saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(7L);
        assertThat(saved.getAction()).isEqualTo("TOOL_EXECUTE");
        assertThat(saved.getResource()).isEqualTo("knowledge_search");
        assertThat(saved.getRiskLevel()).isEqualTo("WARN");
        assertThat(saved.getBlocked()).isEqualTo(1);
        assertThat(saved.getDetails()).isEqualTo("blocked by policy");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("简化接口按 SAFE / 未阻断 落库")
    void simplifiedOverloadDefaultsToSafe() {
        AuditService service = newService();

        service.log(1L, "LOGIN", "/api/auth/login");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getRiskLevel()).isEqualTo("SAFE");
        assertThat(captor.getValue().getBlocked()).isZero();
    }

    @Test
    @DisplayName("安全事件接口落库并带说明")
    void securityEventIsPersisted() {
        AuditService service = newService();

        service.logSecurityEvent(3L, "AUTH_FAILED", "/api/chat", ToolRiskLevel.WARN, true);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("AUTH_FAILED");
        assertThat(captor.getValue().getBlocked()).isEqualTo(1);
        assertThat(captor.getValue().getDetails()).isNotBlank();
    }

    @Test
    @DisplayName("riskLevel 为 null 时回落为 SAFE，不写空字符串")
    void nullRiskLevelFallsBackToSafe() {
        AuditService service = newService();

        service.log(1L, "X", "/y", null, false, null);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getRiskLevel()).isEqualTo("SAFE");
        assertThat(captor.getValue().getBlocked()).isZero();
    }

    @Test
    @DisplayName("超长 details 被截断，避免把行撑爆")
    void longDetailsAreTruncated() {
        AuditService service = newService();

        service.log(1L, "X", "/y", ToolRiskLevel.SAFE, false, "x".repeat(3000));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(mapper).insert(captor.capture());
        String details = captor.getValue().getDetails();
        assertThat(details).hasSize(2000 + "... [truncated]".length());
        assertThat(details).endsWith("... [truncated]");
    }

    @Test
    @DisplayName("写库失败不影响业务（异常在服务内部消化）")
    void databaseFailureIsSwallowed() {
        AuditService service = newService();
        when(mapper.insert(any(AuditLog.class))).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> service.log(1L, "X", "/y"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("审计表不可用时不阻断业务，仅降级为日志")
    void missingMapperDoesNotBreakBusiness() {
        AuditService service = new AuditService(null);

        assertThatCode(() -> service.log(1L, "X", "/y"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Kafka 是旁路：开启时额外投递一份")
    void kafkaIsOptionalSideChannel() {
        AuditService service = newService();
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.<SendResult<String, Object>>completedFuture(null));
        ReflectionTestUtils.setField(service, "kafkaTemplate", kafka);

        service.log(9L, "TOOL_EXECUTE", "run_code", ToolRiskLevel.WARN, false, "details");

        verify(mapper).insert(any(AuditLog.class));
        verify(kafka).send(eq(AuditService.KAFKA_TOPIC_AUDIT_LOG), eq("9"), any(AuditLog.class));
    }

    @Test
    @DisplayName("Kafka 不可用时审计依然落库，且不抛异常")
    void kafkaFailureDoesNotLoseAudit() {
        AuditService service = newService();
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("kafka down"));
        ReflectionTestUtils.setField(service, "kafkaTemplate", kafka);

        assertThatCode(() -> service.log(1L, "X", "/y")).doesNotThrowAnyException();

        verify(mapper).insert(any(AuditLog.class));
    }

    @Test
    @DisplayName("Kafka 未注入时静默跳过旁路，审计仍落库")
    void kafkaNotInjectedMeansNoSend() {
        AuditService service = newService();

        assertThat(ReflectionTestUtils.getField(service, "kafkaTemplate")).isNull();

        service.log(1L, "X", "/y");

        verify(mapper).insert(any(AuditLog.class));
    }

    @Test
    @DisplayName("落库时生成非空 eventId，长度 ≤ 64（匹配 audit_log.event_id 列定义）")
    void eventIdIsGeneratedAndFitsColumn() {
        AuditService service = newService();

        service.log(1L, "X", "/y");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(mapper).insert(captor.capture());

        String eventId = captor.getValue().getEventId();
        assertThat(eventId).isNotBlank();
        assertThat(eventId).hasSizeLessThanOrEqualTo(64);
    }

    @Test
    @DisplayName("每次审计的 eventId 互不相同（幂等键不碰撞，非固定值/时间戳碰撞）")
    void eventIdsAreUniquePerRecord() {
        AuditService service = newService();

        service.log(1L, "A", "/1");
        service.log(2L, "B", "/2");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(mapper, times(2)).insert(captor.capture());

        List<AuditLog> saved = captor.getAllValues();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getEventId()).isNotBlank();
        assertThat(saved.get(1).getEventId()).isNotBlank();
        assertThat(saved.get(0).getEventId()).isNotEqualTo(saved.get(1).getEventId());
    }

    @Test
    @DisplayName("同一 eventId 同时进入 DB 记录与 Kafka 消息体（幂等的依据一致）")
    void eventIdSharedBetweenDbAndKafka() {
        AuditService service = newService();
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.<SendResult<String, Object>>completedFuture(null));
        ReflectionTestUtils.setField(service, "kafkaTemplate", kafka);

        service.log(9L, "TOOL_EXECUTE", "run_code", ToolRiskLevel.WARN, false, "details");

        ArgumentCaptor<AuditLog> dbCaptor = ArgumentCaptor.forClass(AuditLog.class);
        verify(mapper).insert(dbCaptor.capture());
        ArgumentCaptor<Object> kafkaCaptor = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(anyString(), anyString(), kafkaCaptor.capture());

        AuditLog sent = (AuditLog) kafkaCaptor.getValue();
        assertThat(sent.getEventId()).isNotBlank()
                .isEqualTo(dbCaptor.getValue().getEventId());
    }
}
