package com.example.nasprocessor.service;

import com.example.nasprocessor.config.ApiConfig;
import com.example.nasprocessor.model.ApiMessageRequest;
import com.example.nasprocessor.model.ApiMessageResponse;
import com.example.nasprocessor.model.BatchApiResponse;
import com.example.nasprocessor.model.FinalFileResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Writes response files to the NAS response folder.
 *
 * Layout produced for each processed file:
 *
 *   nas/responses/
 *     orders_20240315_120000/              ← per-file subfolder (top-level file)
 *       batch_001_of_003/
 *         request.json
 *         response.json
 *       ...
 *       orders.FINAL.json
 *
 *   When scanRecursive: preserves inbound structure, e.g. folder_a/file_20240315_120000/
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResponseFileWriterService {

    private final ObjectMapper objectMapper;
    private final ApiConfig apiConfig;

    @Value("${nas.folders.responses:/mnt/nas/responses}")
    private String responsesBaseFolder;

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /**
     * Create a per-file subdirectory under the responses base folder.
     * Preserves inbound structure when relativePathFromInbound contains path separators
     * (e.g. "folder_a/file.json" -> responses/folder_a/file_20240315_120000/).
     */
    public Path createResponseFolder(String relativePathFromInbound) throws IOException {
        Path relPath = Path.of(relativePathFromInbound);
        String baseName = stripExtension(relPath.getFileName().toString());
        String folderName = baseName + "_" + LocalDateTime.now().format(TS_FMT);
        Path folder;
        if (relPath.getParent() != null && !relPath.getParent().toString().isEmpty()) {
            folder = Path.of(responsesBaseFolder).resolve(relPath.getParent()).resolve(folderName);
        } else {
            folder = Path.of(responsesBaseFolder, folderName);
        }
        Files.createDirectories(folder);
        log.debug("Created response folder: {}", folder);
        return folder;
    }

    /**
     * Create or return the per-batch subfolder: batch_001_of_005/
     */
    public Path getBatchFolder(Path responseFolder, int batchNumber, int totalBatches) throws IOException {
        String folderName = String.format("batch_%03d_of_%03d", batchNumber, totalBatches);
        Path batchFolder = responseFolder.resolve(folderName);
        Files.createDirectories(batchFolder);
        return batchFolder;
    }

    /**
     * Write the request body sent to the API for a batch.
     * Written to batch_NNN_of_TTT/request.json
     */
    public void writeBatchRequest(Path responseFolder, int batchNumber, int totalBatches,
                                   List<Map<String, Object>> records) {
        try {
            Path batchFolder = getBatchFolder(responseFolder, batchNumber, totalBatches);
            Path filePath = batchFolder.resolve("request.json");

            ApiMessageRequest envelope = ApiMessageRequest.builder()
                .message(ApiMessageRequest.Message.builder()
                        .aboutVersions(ApiMessageRequest.AboutVersions.builder()
                                .aboutVersion(ApiMessageRequest.AboutVersion.builder()
                                        .createdDateTime(LocalDateTime.now().toString())
                                        .aboutVersionIdentifier(apiConfig.getAboutVersionIdentifier())
                                        .requestingSystem(apiConfig.getRequestingSystem())
                                        .build())
                                .build())
                        .requestData(ApiMessageRequest.RequestData.builder()
                                .defaultEventList(records)
                                .build())
                        .build())
                .build();

            objectMapper.writerWithDefaultPrettyPrinter().writeValue(filePath.toFile(), envelope);
            log.debug("Written batch request: {}", filePath);
        } catch (IOException e) {
            log.error("Failed to write batch request file batch_{}_of_{}/request.json: {}",
                    batchNumber, totalBatches, e.getMessage());
        }
    }

    /**
     * Write an individual batch response file.
     * Written to batch_NNN_of_TTT/response.json
     */
    public BatchApiResponse writeBatchResponse(
            Path responseFolder,
            BatchApiResponse batchResponse) {

        try {
            Path batchFolder = getBatchFolder(responseFolder,
                    batchResponse.getBatchNumber(),
                    batchResponse.getTotalBatches());
            Path filePath = batchFolder.resolve("response.json");

            objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValue(filePath.toFile(), batchResponse);

            batchResponse.setResponseFilePath(filePath.toString());
            log.debug("Written batch response: {}", filePath);

        } catch (IOException e) {
            log.error("Failed to write batch response file batch_{}_of_{}/response.json: {}",
                    batchResponse.getBatchNumber(), batchResponse.getTotalBatches(), e.getMessage());
            batchResponse.setResponseFilePath("WRITE_FAILED: " + e.getMessage());
        }

        return batchResponse;
    }

    /**
     * Aggregate all batch responses into a single FINAL response file.
     * Written in the same per-file subfolder as the batch files.
     */
    public FinalFileResponse writeFinalResponse(
            Path responseFolder,
            String sourceFileName,
            List<BatchApiResponse> batchResponses,
            int totalRecords,
            LocalDateTime startTime,
            LocalDateTime endTime) {

        long successfulBatches = batchResponses.stream()
                .filter(b -> b.getStatus() == BatchApiResponse.BatchStatus.SUCCESS)
                .count();
        long failedBatches = batchResponses.stream()
                .filter(b -> b.getStatus() == BatchApiResponse.BatchStatus.FAILED)
                .count();
        int processedRecords = batchResponses.stream()
                .filter(b -> b.getStatus() == BatchApiResponse.BatchStatus.SUCCESS)
                .mapToInt(BatchApiResponse::getRecordCount)
                .sum();

        FinalFileResponse.OverallStatus overallStatus;
        String summary;

        if (failedBatches == 0) {
            overallStatus = FinalFileResponse.OverallStatus.SUCCESS;
            summary = String.format("All %d batches processed successfully. %d/%d records sent.",
                    batchResponses.size(), processedRecords, totalRecords);
        } else if (successfulBatches > 0) {
            overallStatus = FinalFileResponse.OverallStatus.PARTIAL_SUCCESS;
            summary = String.format("%d/%d batches succeeded, %d failed. %d/%d records sent.",
                    successfulBatches, batchResponses.size(), failedBatches,
                    processedRecords, totalRecords);
        } else {
            overallStatus = FinalFileResponse.OverallStatus.FAILED;
            summary = String.format("All %d batches failed. 0/%d records sent.",
                    batchResponses.size(), totalRecords);
        }

        long durationMs = java.time.Duration.between(startTime, endTime).toMillis();

        // Use filename part only for FINAL file (e.g. folder_a/file.json -> file.FINAL.json)
        String baseFileName = Path.of(sourceFileName).getFileName().toString();
        String finalFileName = stripExtension(baseFileName) + ".FINAL.json";
        Path finalFilePath = responseFolder.resolve(finalFileName);

        ApiMessageResponse.ResponseData responseData = ApiMessageResponse.ResponseData.builder()
                .sourceFile(sourceFileName)
                .overallStatus(overallStatus)
                .processingStartTime(startTime)
                .processingEndTime(endTime)
                .totalDurationMs(durationMs)
                .totalRecords(totalRecords)
                .totalBatches(batchResponses.size())
                .successfulBatches((int) successfulBatches)
                .failedBatches((int) failedBatches)
                .processedRecords(processedRecords)
                .summaryMessage(summary)
                .batchResponses(batchResponses)
                .batchResponseFolder(responseFolder.toString())
                .finalResponseFilePath(finalFilePath.toString())
                .build();

        ApiMessageResponse envelope = ApiMessageResponse.builder()
                .message(ApiMessageResponse.Message.builder()
                        .aboutVersions(ApiMessageResponse.AboutVersions.builder()
                                .aboutVersion(ApiMessageResponse.AboutVersion.builder()
                                        .createdDateTime(java.time.LocalDateTime.now().toString())
                                        .aboutVersionIdentifier(apiConfig.getAboutVersionIdentifier())
                                        .requestingSystem(apiConfig.getRequestingSystem())
                                        .build())
                                .build())
                        .responseData(responseData)
                        .build())
                .build();

        try {
            objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValue(finalFilePath.toFile(), envelope);
            log.info("Written final response: {}", finalFilePath);
        } catch (IOException e) {
            log.error("Failed to write final response file: {}", e.getMessage());
        }

        return FinalFileResponse.builder()
                .sourceFile(sourceFileName)
                .overallStatus(overallStatus)
                .processingStartTime(startTime)
                .processingEndTime(endTime)
                .totalDurationMs(durationMs)
                .totalRecords(totalRecords)
                .totalBatches(batchResponses.size())
                .successfulBatches((int) successfulBatches)
                .failedBatches((int) failedBatches)
                .processedRecords(processedRecords)
                .summaryMessage(summary)
                .batchResponses(batchResponses)
                .batchResponseFolder(responseFolder.toString())
                .finalResponseFilePath(finalFilePath.toString())
                .build();
    }

    // -------------------------------------------------------------------------

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot == -1 ? fileName : fileName.substring(0, dot);
    }
}
