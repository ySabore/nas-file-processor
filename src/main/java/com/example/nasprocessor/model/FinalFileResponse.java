package com.example.nasprocessor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Final consolidated response file written after all batches of a single
 * JSON file have been processed.  Aggregates all per-batch responses.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinalFileResponse {

    public enum OverallStatus {
        SUCCESS,          // all batches succeeded
        PARTIAL_SUCCESS,  // some batches succeeded, some failed
        FAILED            // all batches failed (or file could not be read)
    }

    // ---- File-level metadata ----
    private String sourceFile;
    private OverallStatus overallStatus;
    private LocalDateTime processingStartTime;
    private LocalDateTime processingEndTime;
    private long totalDurationMs;

    // ---- Record counts ----
    private int totalRecords;
    private int totalBatches;
    private int successfulBatches;
    private int failedBatches;
    private int processedRecords;

    // ---- Summary ----
    private String summaryMessage;

    // ---- Per-batch detail ----
    private List<BatchApiResponse> batchResponses;

    // ---- Paths ----
    /** Folder where per-batch response files were written */
    private String batchResponseFolder;

    /** Path of this final consolidated response file */
    private String finalResponseFilePath;
}
