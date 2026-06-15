package com.agi.assistant.service.harness;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 超时配置
 * <p>
 * 定义各子系统的超时时间（毫秒），支持通过 application.yml 覆盖。
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "harness.timeout")
public class TimeoutConfig {

    /** LLM 调用超时，默认 30 秒 */
    private long llmTimeout = 30_000L;

    /** 工具调用超时，默认 10 秒 */
    private long toolTimeout = 10_000L;

    /** RAG 检索超时，默认 5 秒 */
    private long ragTimeout = 5_000L;

    /** MCP 协议调用超时，默认 15 秒 */
    private long mcpTimeout = 15_000L;

    @PostConstruct
    public void init() {
        log.info("TimeoutConfig initialized: llm={}ms, tool={}ms, rag={}ms, mcp={}ms",
                llmTimeout, toolTimeout, ragTimeout, mcpTimeout);
    }
}
