package com.example.nasprocessor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Represents the result of processing a single file.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileProcessingResult {

    public enum Status {
        SUCCESS, PARTIAL_SUCCESS, FAILED, SKIPPED
    }

    private String fileName;
    private Status status;
    private int totalRecords;
    private int processedRecords;
    private int failedBatches;
    private String errorMessage;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private long durationMs;
    /** Path to the FINAL.json response file (when processing completed) */
    private String finalResponseFilePath;
}
