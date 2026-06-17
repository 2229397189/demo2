package com.agi.assistant.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import io.milvus.client.MilvusClient;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.R;
import io.milvus.param.collection.HasCollectionParam;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
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

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_INTERVAL_MS = 5000;

    @Bean
    public MilvusServiceClient milvusClient() {
        // Suppress Milvus SDK internal ERROR logs during connection attempt
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger milvusLogger = loggerContext.getLogger("io.milvus.client.AbstractMilvusGrpcClient");
        Level originalLevel = milvusLogger.getEffectiveLevel();
        milvusLogger.setLevel(Level.WARN);

        try {
            ConnectParam connectParam = ConnectParam.newBuilder()
                    .withHost(host != null ? host : "localhost")
                    .withPort(port > 0 ? port : 19530)
                    .withDatabaseName(database != null ? database : "default")
                    .withIdleTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .withConnectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .withKeepAliveTime(60, java.util.concurrent.TimeUnit.SECONDS)
                    .withKeepAliveTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                    .build();

            // Retry connection to handle slow Milvus startup
            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                try {
                    this.milvusClient = new MilvusServiceClient(connectParam);
                    R<Boolean> response = milvusClient.hasCollection(
                            HasCollectionParam.newBuilder()
                                    .withCollectionName((collectionPrefix != null ? collectionPrefix : "agi_") + "vectors")
                                    .build()
                    );
                    log.info("Milvus connection established to {}:{}", host, port);
                    return this.milvusClient;
                } catch (Exception e) {
                    if (this.milvusClient != null) {
                        try { this.milvusClient.close(); } catch (Exception ignored) {}
                    }
                    if (attempt < MAX_RETRIES) {
                        log.warn("Milvus connection attempt {}/{} failed: {}. Retrying in {}s...",
                                attempt, MAX_RETRIES, e.getMessage(), RETRY_INTERVAL_MS / 1000);
                        try { Thread.sleep(RETRY_INTERVAL_MS); } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    } else {
                        log.warn("Milvus connection failed after {} attempts: {}. Milvus features will be unavailable.", MAX_RETRIES, e.getMessage());
                    }
                }
            }
            return null;
        } finally {
            milvusLogger.setLevel(originalLevel);
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
