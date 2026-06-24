package com.agi.assistant.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PreDestroy;

import java.util.concurrent.TimeUnit;

@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "neo4j")
@ConditionalOnProperty(name = "neo4j.enabled", havingValue = "true", matchIfMissing = false)
public class Neo4jConfig {

    @Value("${neo4j.uri:bolt://localhost:7687}")
    private String uri;

    @Value("${neo4j.authentication.username:neo4j}")
    private String username;

    @Value("${neo4j.authentication.password:neo4j123456}")
    private String password;

    private int maxConnectionPoolSize = 5;
    private long connectionAcquisitionTimeout = 5;
    private long connectionTimeout = 5;
    private long maxTransactionRetryTime = 5;

    private Driver driver;

    @Bean
    public Driver neo4jDriver() {
        log.info("Initializing Neo4j driver with URI: {}, username: {}", uri, username);
        try {
            this.driver = GraphDatabase.driver(
                    uri,
                    AuthTokens.basic(username, password),
                    org.neo4j.driver.Config.builder()
                            .withMaxConnectionPoolSize(maxConnectionPoolSize > 0 ? maxConnectionPoolSize : 5)
                            .withConnectionAcquisitionTimeout(
                                    connectionAcquisitionTimeout > 0 ? connectionAcquisitionTimeout : 5,
                                    TimeUnit.SECONDS)
                            .withConnectionTimeout(connectionTimeout > 0 ? connectionTimeout : 5, TimeUnit.SECONDS)
                            .withMaxTransactionRetryTime(maxTransactionRetryTime > 0 ? maxTransactionRetryTime : 5, TimeUnit.SECONDS)
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
