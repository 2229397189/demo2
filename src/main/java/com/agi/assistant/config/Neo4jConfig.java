package com.agi.assistant.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PreDestroy;

@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "neo4j")
@ConditionalOnProperty(prefix = "neo4j", name = "enabled", havingValue = "true")
public class Neo4jConfig {

    private String uri;
    private Authentication authentication = new Authentication();
    private int maxConnectionPoolSize;
    private long connectionAcquisitionTimeout;

    @Data
    public static class Authentication {
        private String username;
        private String password;
    }

    private Driver driver;

    @Bean
    public Driver neo4jDriver() {
        try {
            this.driver = GraphDatabase.driver(
                    uri != null ? uri : "bolt://localhost:7687",
                    AuthTokens.basic(
                            authentication.getUsername() != null ? authentication.getUsername() : "neo4j",
                            authentication.getPassword() != null ? authentication.getPassword() : "neo4j123456"),
                    org.neo4j.driver.Config.builder()
                            .withMaxConnectionPoolSize(maxConnectionPoolSize > 0 ? maxConnectionPoolSize : 100)
                            .withConnectionAcquisitionTimeout(
                                    connectionAcquisitionTimeout > 0 ? connectionAcquisitionTimeout * 1000 : 30000,
                                    java.util.concurrent.TimeUnit.MILLISECONDS)
                            .withConnectionTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                            .withMaxTransactionRetryTime(30, java.util.concurrent.TimeUnit.SECONDS)
                            .build()
            );

            try {
                driver.verifyConnectivity();
                log.info("Neo4j connection established to {}", uri);
                return this.driver;
            } catch (Exception e) {
                log.warn("Neo4j connectivity check failed: {}. Graph features will be unavailable.", e.getMessage());
                driver.close();
                return null;
            }
        } catch (Exception e) {
            log.warn("Failed to create Neo4j driver: {}. Graph features will be unavailable.", e.getMessage());
            return null;
        }
    }

    @PreDestroy
    public void closeDriver() {
        if (driver != null) {
            driver.close();
            log.info("Neo4j driver connection closed");
        }
    }
}
