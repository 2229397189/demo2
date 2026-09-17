package com.agi.assistant.service.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agi.assistant.mapper.AuditLogMapper;
import com.agi.assistant.model.entity.AuditLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AuditKafkaConsumer} 离线单测。
 * <p>
 * 不启动 Spring 上下文、不连真实 Kafka、不连真实 DB：{@link AuditLogMapper} 用
 * Mockito 桩模拟，并复刻 {@code audit_log.event_id} 唯一索引的语义
 * （首次 {@code INSERT IGNORE} 返回 1，重复返回 0），用来验证审计消费闭环的幂等行为。
 * <p>
 * 关注点有两层：① 重复消息不产生重复行也不报错；② 消费者作为旁路，任何失败都
 * 不向容器抛出（避免无限重试刷屏），且不得记 error 级日志（避免误告警）。
 */
class AuditKafkaConsumerTest {

    private AuditLogMapper mapper;
    private AuditKafkaConsumer consumer;

    private Logger consumerLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        mapper = mock(AuditLogMapper.class);
        consumer = new AuditKafkaConsumer(mapper);

        // 捕获消费者 logger 输出，用于断言日志级别（不得出现 error）。
        consumerLogger = (Logger) LoggerFactory.getLogger(AuditKafkaConsumer.class);
        appender = new ListAppender<>();
        appender.start();
        consumerLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        if (consumerLogger != null && appender != null) {
            consumerLogger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("同一 eventId 投递两次：两次调用 insertIgnore，仅第一次生效，无 error 日志、不抛异常")
    void duplicateEventIsIgnoredIdempotently() {
        Set<String> persisted = installUniqueIndexSimulation();
        AuditLog message = auditLog("evt-dedup-1", "TOOL_EXECUTE");

        assertThatCode(() -> consumer.onMessage(message)).doesNotThrowAnyException();
        assertThatCode(() -> consumer.onMessage(message)).doesNotThrowAnyException();

        // 消费者确实处理了两次（不是直接丢弃）
        verify(mapper, times(2)).insertIgnore(any(AuditLog.class));
        // 但唯一索引语义下只有第一次真正写入
        assertThat(persisted).containsExactly("evt-dedup-1");
        // 重复是正常路径，绝不能报 error
        assertThat(errorEvents()).isEmpty();
    }

    @Test
    @DisplayName("两条不同 eventId 的消息：两条都生效")
    void distinctEventsBothPersist() {
        Set<String> persisted = installUniqueIndexSimulation();

        consumer.onMessage(auditLog("evt-A", "LOGIN"));
        consumer.onMessage(auditLog("evt-B", "TOOL_EXECUTE"));

        verify(mapper, times(2)).insertIgnore(any(AuditLog.class));
        assertThat(persisted).containsExactlyInAnyOrder("evt-A", "evt-B");
        assertThat(errorEvents()).isEmpty();
    }

    @Test
    @DisplayName("mapper 抛异常时消费者不向外抛（旁路失败不影响容器），且仅记 warn")
    void mapperExceptionIsSwallowed() {
        when(mapper.insertIgnore(any(AuditLog.class))).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> consumer.onMessage(auditLog("evt-err", "AUTH_FAILED")))
                .doesNotThrowAnyException();

        // 失败走 warn，而不是 error（避免误告警）
        assertThat(errorEvents()).isEmpty();
        assertThat(warnEvents()).isNotEmpty();
    }

    @Test
    @DisplayName("空消息被安全跳过，不调用 mapper、不抛异常")
    void nullMessageIsSkipped() {
        assertThatCode(() -> consumer.onMessage(null)).doesNotThrowAnyException();
        verify(mapper, times(0)).insertIgnore(any(AuditLog.class));
        assertThat(errorEvents()).isEmpty();
    }

    /**
     * 安装「唯一索引」模拟：以 eventId 去重，首次返回 1（写入），重复返回 0（忽略）。
     *
     * @return 记录真正写入的 eventId 集合
     */
    private Set<String> installUniqueIndexSimulation() {
        Set<String> persisted = ConcurrentHashMap.newKeySet();
        when(mapper.insertIgnore(any(AuditLog.class))).thenAnswer(invocation -> {
            AuditLog log = invocation.getArgument(0);
            return persisted.add(log.getEventId()) ? 1 : 0;
        });
        return persisted;
    }

    private AuditLog auditLog(String eventId, String action) {
        AuditLog log = new AuditLog();
        log.setEventId(eventId);
        log.setAction(action);
        log.setResource("/api/x");
        return log;
    }

    private List<ILoggingEvent> errorEvents() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
    }

    private List<ILoggingEvent> warnEvents() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }
}
