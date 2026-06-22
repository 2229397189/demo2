package com.agi.assistant.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "milvus")
@ConditionalOnProperty(prefix = "milvus", name = "enabled", havingValue = "true")
public class MilvusConfig {

    private String host;
    private int port;
    private String database;
    private String collectionPrefix;
    private long connectTimeoutSeconds = 3;
    private long keepAliveSeconds = 30;

    private MilvusServiceClient milvusClient;

    @Bean
    public MilvusServiceClient milvusClient() {
        String resolvedHost = host != null ? host : "localhost";
        int resolvedPort = port > 0 ? port : 19530;
        try {
            ConnectParam connectParam = ConnectParam.newBuilder()
                    .withHost(resolvedHost)
                    .withPort(resolvedPort)
                    .withDatabaseName(database != null ? database : "default")
                    .withIdleTimeout(keepAliveSeconds, TimeUnit.SECONDS)
                    .withConnectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
                    .withKeepAliveTime(keepAliveSeconds, TimeUnit.SECONDS)
                    .withKeepAliveTimeout(Math.max(1, connectTimeoutSeconds), TimeUnit.SECONDS)
                    .build();

            this.milvusClient = new MilvusServiceClient(connectParam);
            log.info("Milvus connection established to {}:{}", resolvedHost, resolvedPort);
            return this.milvusClient;
        } catch (Exception e) {
            closeClientQuietly();
            log.warn("Milvus unavailable at {}:{} after {}s. Vector search will be disabled: {}",
                    resolvedHost, resolvedPort, connectTimeoutSeconds, e.getMessage());
            return null;
        }
    }

    @PreDestroy
    public void closeClient() {
        if (milvusClient != null) {
            milvusClient.close();
            log.info("Milvus client connection closed");
        }
    }

    private void closeClientQuietly() {
        if (milvusClient != null) {
            try {
                milvusClient.close();
            } catch (Exception ignored) {
            }
            milvusClient = null;
        }
    }
}
