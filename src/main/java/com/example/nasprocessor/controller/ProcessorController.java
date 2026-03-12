package com.example.nasprocessor.controller;

import com.example.nasprocessor.model.FileProcessingResult;
import com.example.nasprocessor.service.ArchiveService;
import com.example.nasprocessor.service.FileOrchestrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST endpoints used by ControlM to trigger file processing.
 *
 * ControlM job command:
 *   curl -X POST http://<host>:8080/api/processor/trigger
 *
 * Check job status:
 *   curl http://<host>:8080/api/processor/status
 */
@Slf4j
@RestController
@RequestMapping("/api/processor")
@RequiredArgsConstructor
public class ProcessorController {

    private final FileOrchestrationService orchestrationService;

    @Autowired(required = false)
    private ArchiveService archiveService;

    /**
     * ControlM calls this endpoint every 10 minutes to trigger a processing cycle.
     * Returns 200 with a summary of processed files.
     * Returns 409 if a cycle is already running.
     */
    @PostMapping("/trigger")
    public ResponseEntity<Map<String, Object>> trigger() {
        log.info("Processing trigger received via REST (ControlM)");

        if (orchestrationService.isRunning()) {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "SKIPPED");
            response.put("message", "Processing cycle already in progress");
            response.put("timestamp", LocalDateTime.now().toString());
            return ResponseEntity.status(409).body(response);
        }

        List<FileProcessingResult> results = orchestrationService.runProcessingCycle();

        long succeeded = results.stream()
                .filter(r -> r.getStatus() == FileProcessingResult.Status.SUCCESS)
                .count();
        long partial = results.stream()
                .filter(r -> r.getStatus() == FileProcessingResult.Status.PARTIAL_SUCCESS)
                .count();
        long failed = results.stream()
                .filter(r -> r.getStatus() == FileProcessingResult.Status.FAILED)
                .count();

        // Group details by folder (e.g. servicer_A, servicer_B) for structured response
        Map<String, List<FileProcessingResult>> detailsByFolder = results.stream()
                .collect(Collectors.groupingBy(
                        r -> {
                            String fn = r.getFileName();
                            int slash = fn.indexOf('/');
                            return slash > 0 ? fn.substring(0, slash) : "root";
                        },
                        LinkedHashMap::new,
                        Collectors.toList()));

        Map<String, Object> response = new HashMap<>();
        response.put("status", failed > 0 ? "COMPLETED_WITH_ERRORS" : "SUCCESS");
        response.put("filesFound", results.size());
        response.put("succeeded", succeeded);
        response.put("partial", partial);
        response.put("failed", failed);
        response.put("timestamp", LocalDateTime.now().toString());
        response.put("details", detailsByFolder);

        // Return 207 Multi-Status if there were partial failures so ControlM can detect issues
        int httpStatus = failed > 0 ? 207 : 200;
        return ResponseEntity.status(httpStatus).body(response);
    }

    /**
     * ControlM or ops team can poll this to check current state.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> response = new HashMap<>();
        response.put("running", orchestrationService.isRunning());
        response.put("timestamp", LocalDateTime.now().toString());
        return ResponseEntity.ok(response);
    }

    /**
     * ControlM can call this endpoint (e.g. daily) to run the archive job.
     * Moves files older than nas.archive.retention-days to nas/archive/YYYY-MM/.
     * Returns 503 if archive is disabled (nas.archive.enabled=false).
     */
    @PostMapping("/archive")
    public ResponseEntity<Map<String, Object>> triggerArchive() {
        if (archiveService == null) {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "DISABLED");
            response.put("message", "Archive is disabled (nas.archive.enabled=false)");
            response.put("timestamp", LocalDateTime.now().toString());
            return ResponseEntity.status(503).body(response);
        }
        log.info("Archive trigger received via REST (ControlM)");
        var result = archiveService.runArchive();
        Map<String, Object> response = new HashMap<>();
        response.put("status", result.isSuccess() ? "SUCCESS" : "FAILED");
        response.put("ran", result.isRan());
        response.put("completedCount", result.getCompletedCount());
        response.put("errorCount", result.getErrorCount());
        response.put("responsesCount", result.getResponsesCount());
        if (result.getErrorMessage() != null) {
            response.put("errorMessage", result.getErrorMessage());
        }
        response.put("timestamp", LocalDateTime.now().toString());
        int httpStatus = result.isSuccess() ? 200 : 500;
        return ResponseEntity.status(httpStatus).body(response);
    }

    /**
     * Health check for load balancers / ControlM pre-checks.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "nas-file-processor",
                "timestamp", LocalDateTime.now().toString()
        ));
    }
}
