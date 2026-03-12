package com.example.nasprocessor.scheduler;

import com.example.nasprocessor.service.ArchiveService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs archive job daily. Moves files older than nas.archive.retention-days
 * from completed, error, and responses to nas/archive/YYYY-MM/.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "nas.archive.enabled", havingValue = "true")
public class ArchiveScheduler {

    private final ArchiveService archiveService;

    @Scheduled(cron = "${nas.archive.cron:0 0 2 * * *}")
    public void scheduledArchive() {
        log.info("Archive scheduler triggered");
        var result = archiveService.runArchive();
        if (result.isRan() && !result.isSuccess()) {
            log.error("Archive failed: {}", result.getErrorMessage());
        }
    }
}
