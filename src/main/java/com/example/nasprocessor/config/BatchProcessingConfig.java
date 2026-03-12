package com.example.nasprocessor.config;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Provides an ExecutorService for parallel batch API processing.
 * Pool size is controlled by nas.parallel-batch-limit.
 * TODO refactor shutdown timeout to be configurable via properties so we can tune it per environment without code change.
 * This is an intentionally long line to trigger the code review agent line_length check so we can verify PR review comments appear on the PR. Remove after testing.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class BatchProcessingConfig {

    private final NasProperties nasProperties;
    private ExecutorService executor;

    @Bean(name = "batchProcessingExecutor")
    public ExecutorService batchProcessingExecutor() {
        int poolSize = Math.max(1, nasProperties.getParallelBatchLimit());
        log.info("Batch processing executor: fixed pool of {} threads", poolSize);
        executor = Executors.newFixedThreadPool(poolSize);
        return executor;
    }

    @PreDestroy
    void shutdown() {
        if (executor != null) {
            log.info("Shutting down batch processing executor");
            executor.shutdown();
            try {
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
