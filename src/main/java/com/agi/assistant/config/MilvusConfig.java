package com.agi.assistant.config;

import io.milvus.client.MilvusClient;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.R;
import io.milvus.param.collection.HasCollectionParam;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "milvus")
public class MilvusConfig {

    private String host;
    private int port;
    private String database;
    private String collectionPrefix;

    private MilvusServiceClient milvusClient;

    @Bean
    public MilvusServiceClient milvusClient() {
        try {
            ConnectParam connectParam = ConnectParam.newBuilder()
                    .withHost(host != null ? host : "localhost")
                    .withPort(port > 0 ? port : 19530)
                    .withDatabaseName(database != null ? database : "default")
                    .withIdleTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .withConnectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .build();

            this.milvusClient = new MilvusServiceClient(connectParam);

            try {
                R<Boolean> response = milvusClient.hasCollection(
                        HasCollectionParam.newBuilder()
                                .withCollectionName((collectionPrefix != null ? collectionPrefix : "doc") + "_vectors")
                                .build()
                );
                log.info("Milvus connection established to {}:{}", host, port);
            } catch (Exception e) {
                log.warn("Milvus connection established but collection check failed: {}", e.getMessage());
            }

            return this.milvusClient;
        } catch (Exception e) {
            log.warn("Failed to create Milvus client: {}. Milvus features will be unavailable.", e.getMessage());
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
}
