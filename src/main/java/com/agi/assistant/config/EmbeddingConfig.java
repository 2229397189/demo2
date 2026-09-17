package com.agi.assistant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 嵌入模型配置
 * <p>
 * 支持三种 provider 模式：
 * <ul>
 *   <li>{@code remote} —— 只调远程 API，失败即返回空向量</li>
 *   <li>{@code local}  —— 只使用本地哈希向量降级实现（离线、零依赖、非语义）</li>
 *   <li>{@code auto}   —— 优先远程，失败后自动降级到本地哈希向量</li>
 * </ul>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "embedding")
public class EmbeddingConfig {

    /**
     * provider 模式：remote / local / auto
     */
    private String provider = "auto";

    /**
     * 模型 API 基础地址
     */
    private String baseUrl = "https://api.openai.com/v1";

    /**
     * API 密钥
     */
    private String apiKey = "";

    /**
     * 模型名称
     */
    private String model = "text-embedding";

    /**
     * 向量维度（必须与 Milvus collection 维度一致）
     */
    private int dimensions = 1024;
}
