package com.agi.assistant.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 统一线程池配置
 * <p>
 * 将原来散落在各处的 Executors.newFixedThreadPool() 替换为
 * Spring 管理的 ThreadPoolTaskExecutor Bean，便于统一监控、优雅停机。
 */
@Configuration
public class AsyncConfig {

    /**
     * 混合检索专用线程池（HybridRetrievalService 使用）
     */
    @Bean("retrievalExecutor")
    public ThreadPoolTaskExecutor retrievalExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("retrieval-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * DAG 调度器专用线程池（DAGScheduler 使用）
     */
    @Bean("dagExecutor")
    public ThreadPoolTaskExecutor dagExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int cores = Runtime.getRuntime().availableProcessors();
        executor.setCorePoolSize(cores);
        executor.setMaxPoolSize(cores * 2);
        executor.setQueueCapacity(128);
        executor.setThreadNamePrefix("dag-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * 竞赛策略专用线程池（RaceStrategy 使用）
     */
    @Bean("raceExecutor")
    public ThreadPoolTaskExecutor raceExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int cores = Runtime.getRuntime().availableProcessors();
        executor.setCorePoolSize(cores * 2);
        executor.setMaxPoolSize(cores * 4);
        executor.setQueueCapacity(128);
        executor.setThreadNamePrefix("race-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
