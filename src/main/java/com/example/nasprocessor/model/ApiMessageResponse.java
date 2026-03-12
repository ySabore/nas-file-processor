package com.example.nasprocessor.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Response envelope for the final consolidated response file.
 * Mirrors the request format with message.aboutVersions + message.responseData.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiMessageResponse {

    private Message message;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        private AboutVersions aboutVersions;
        private ResponseData responseData;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AboutVersions {
        private AboutVersion aboutVersion;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AboutVersion {
        private String createdDateTime;
        private String aboutVersionIdentifier;
        private String requestingSystem;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResponseData {
        private String sourceFile;
        private FinalFileResponse.OverallStatus overallStatus;
        private LocalDateTime processingStartTime;
        private LocalDateTime processingEndTime;
        private long totalDurationMs;
        private int totalRecords;
        private int totalBatches;
        private int successfulBatches;
        private int failedBatches;
        private int processedRecords;
        private String summaryMessage;
        private List<BatchApiResponse> batchResponses;
        private String batchResponseFolder;
        private String finalResponseFilePath;
    }
}
