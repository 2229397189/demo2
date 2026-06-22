package com.agi.assistant.config;

import com.agi.assistant.service.rag.BM25Service;
import com.agi.assistant.service.rag.MilvusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Slf4j
@Order(10)
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.initializer", name = "enabled", havingValue = "true")
public class RagInitializer implements ApplicationRunner {

    private final MilvusService milvusService;
    private final BM25Service bm25Service;

    @Override
    public void run(ApplicationArguments args) {
        log.info("Initializing RAG infrastructure...");

        // Initialize Milvus collection
        try {
            milvusService.createCollection();
            log.info("Milvus collection initialized");
        } catch (Exception e) {
            log.warn("Milvus initialization failed, vector search will be unavailable: {}", e.getMessage());
        }

        // Initialize Elasticsearch index
        try {
            bm25Service.createIndex();
            log.info("Elasticsearch index initialized");
        } catch (Exception e) {
            log.warn("Elasticsearch index initialization failed, BM25 search will be unavailable: {}", e.getMessage());
        }

        log.info("RAG infrastructure initialization completed");
    }
}
