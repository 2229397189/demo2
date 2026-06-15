package com.agi.assistant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 嵌入模型（小米模型）配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "embedding")
public class EmbeddingConfig {

    /**
     * 模型 API 基础地址
     */
    private String baseUrl = "https://api.xiaomi.com/v1";

    /**
     * API 密钥
     */
    private String apiKey = "";

    /**
     * 模型名称
     */
    private String model = "text-embedding";

    /**
     * 向量维度
     */
    private int dimensions = 1024;
}
