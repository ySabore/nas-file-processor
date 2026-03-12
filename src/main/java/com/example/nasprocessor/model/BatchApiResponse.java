package com.example.nasprocessor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Captures the response from the external API for a single batch call.
 * Written to a per-batch response JSON file.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchApiResponse {

    public enum BatchStatus {
        SUCCESS, FAILED, SKIPPED
    }

    private String sourceFile;
    private int batchNumber;
    private int totalBatches;
    private int recordCount;
    private BatchStatus status;

    /** Raw response body returned by the API */
    private String apiResponseBody;

    /** HTTP status code from the API */
    private int httpStatusCode;

    /** Error message if the batch failed */
    private String errorMessage;

    private LocalDateTime requestTime;
    private LocalDateTime responseTime;
    private long durationMs;

    /** Path where this batch response file was written */
    private String responseFilePath;
}
