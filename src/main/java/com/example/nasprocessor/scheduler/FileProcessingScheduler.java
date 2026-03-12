package com.example.nasprocessor.scheduler;

import com.example.nasprocessor.service.FileOrchestrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fallback scheduler — runs every 10 minutes via cron.
 * In production, ControlM should call the REST trigger endpoint instead.
 * Disable via: scheduler.enabled=false
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class FileProcessingScheduler {

    private final FileOrchestrationService orchestrationService;

    @Scheduled(cron = "${scheduler.cron:0 */10 * * * *}")
    public void scheduledRun() {
        log.info("Scheduler triggered processing cycle");
        orchestrationService.runProcessingCycle();
    }
}
