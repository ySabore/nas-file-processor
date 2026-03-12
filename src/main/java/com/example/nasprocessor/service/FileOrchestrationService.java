package com.example.nasprocessor.service;

import com.example.nasprocessor.model.FileProcessingResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileOrchestrationService {

    private final FileManagementService fileManagementService;
    private final JsonFileProcessorService jsonFileProcessorService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private EmailNotificationService emailNotificationService;

    // Prevent overlapping runs if ControlM triggers while a previous run is active
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Called by the scheduler or the ControlM REST trigger.
     * Returns a summary of what was processed.
     */
    public List<FileProcessingResult> runProcessingCycle() {
        List<FileProcessingResult> results = new ArrayList<>();

        if (!running.compareAndSet(false, true)) {
            log.warn("Processing cycle already in progress — skipping this trigger");
            return results;
        }

        try {
            log.info("--- Processing cycle started ---");
            List<Path> inboundFiles = fileManagementService.getInboundFiles();

            if (inboundFiles.isEmpty()) {
                log.info("No files found in inbound folder. Nothing to do.");
                return results;
            }

            for (Path file : inboundFiles) {
                FileProcessingResult result = jsonFileProcessorService.processFile(file);
                results.add(result);
                logResult(result);
            }

            log.info("--- Processing cycle complete. {} file(s) handled ---", results.size());

            if (emailNotificationService != null && !results.isEmpty()) {
                emailNotificationService.sendFinalResponseSummary(results);
            }
        } finally {
            running.set(false);
        }

        return results;
    }

    public boolean isRunning() {
        return running.get();
    }

    private void logResult(FileProcessingResult r) {
        switch (r.getStatus()) {
            case SUCCESS ->
                log.info("[OK]     {} — {} records in {}ms", r.getFileName(), r.getProcessedRecords(), r.getDurationMs());
            case PARTIAL_SUCCESS ->
                log.warn("[PARTIAL] {} — {}/{} records, {} batches failed. {}",
                        r.getFileName(), r.getProcessedRecords(), r.getTotalRecords(),
                        r.getFailedBatches(), r.getErrorMessage());
            case FAILED ->
                log.error("[FAILED] {} — {}", r.getFileName(), r.getErrorMessage());
            default ->
                log.info("[SKIP]   {}", r.getFileName());
        }
    }
}
