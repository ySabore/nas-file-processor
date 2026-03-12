package com.example.nasprocessor.service;

import com.example.nasprocessor.config.NasProperties;
import com.example.nasprocessor.exception.FileReadException;
import com.example.nasprocessor.model.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
public class JsonFileProcessorService {

    private final NasProperties nasProperties;
    private final ApiCallerService apiCallerService;
    private final ExecutorService batchProcessingExecutor;
    private final FileManagementService fileManagementService;
    private final ResponseFileWriterService responseFileWriterService;
    private final ObjectMapper objectMapper;

    public JsonFileProcessorService(NasProperties nasProperties,
                                    ApiCallerService apiCallerService,
                                    @Qualifier("batchProcessingExecutor") ExecutorService batchProcessingExecutor,
                                    FileManagementService fileManagementService,
                                    ResponseFileWriterService responseFileWriterService,
                                    ObjectMapper objectMapper) {
        this.nasProperties = nasProperties;
        this.apiCallerService = apiCallerService;
        this.batchProcessingExecutor = batchProcessingExecutor;
        this.fileManagementService = fileManagementService;
        this.responseFileWriterService = responseFileWriterService;
        this.objectMapper = objectMapper;
    }

    /**
     * Main entry point: read → batch → send (parallel) → write responses → move file.
     */
    public FileProcessingResult processFile(Path inboundFile) {
        Path inboundDir = Path.of(nasProperties.getFolders().getInbound());
        String relativePathStr = inboundDir.relativize(inboundFile).toString();
        String fileName = inboundFile.getFileName().toString();
        LocalDateTime startTime = LocalDateTime.now();
        log.info("=== Starting processing of file: {} ===", relativePathStr);

        Path processingFile = null;
        Path responseFolder = null;

        try {
            // 1. Create response folder (preserves inbound structure when scanRecursive)
            responseFolder = responseFileWriterService.createResponseFolder(relativePathStr);

            // 2. Copy to processing folder (original stays in inbound; lock prevents re-pickup)
            processingFile = fileManagementService.copyToProcessing(inboundFile);

            // 3. Parse JSON: support message envelope (message.requestData.defaultEventList) or legacy format
            List<Map<String, Object>> allRecords = readAndPrepareRecords(processingFile);
            log.info("Parsed {} records from {} (after deduplication)", allRecords.size(), fileName);

            int totalRecords = allRecords.size();
            int batchSize    = nasProperties.getBatchSize();
            int totalBatches = (int) Math.ceil((double) totalRecords / batchSize);

            // 4a. Build all batch requests and write request files (sequential, fast)
            List<BatchApiRequest> batchRequests = new ArrayList<>(totalBatches);
            for (int i = 0; i < totalBatches; i++) {
                int fromIdx = i * batchSize;
                int toIdx   = Math.min(fromIdx + batchSize, totalRecords);
                List<Map<String, Object>> batchRecords = allRecords.subList(fromIdx, toIdx);

                BatchApiRequest batchRequest = BatchApiRequest.builder()
                        .sourceFile(relativePathStr)
                        .batchNumber(i + 1)
                        .totalBatches(totalBatches)
                        .batchSize(batchRecords.size())
                        .records(new ArrayList<>(batchRecords))
                        .build();
                batchRequests.add(batchRequest);

                responseFileWriterService.writeBatchRequest(
                        responseFolder, i + 1, totalBatches, batchRecords);
            }

            // 4b. Send all batches in parallel via CompletableFuture
            List<CompletableFuture<BatchApiResponse>> futures = batchRequests.stream()
                    .map(req -> CompletableFuture.supplyAsync(
                            () -> apiCallerService.sendBatch(req),
                            batchProcessingExecutor))
                    .toList();

            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

            // 5. Collect responses in order and write per-batch response files
            List<BatchApiResponse> batchResponses = new ArrayList<>(totalBatches);
            for (int i = 0; i < totalBatches; i++) {
                BatchApiResponse batchResponse = futures.get(i).join();
                batchResponse = responseFileWriterService.writeBatchResponse(responseFolder, batchResponse);
                batchResponses.add(batchResponse);
                log.debug("Batch {}/{} - status={}, responseFile={}",
                        i + 1, totalBatches, batchResponse.getStatus(),
                        batchResponse.getResponseFilePath());
            }

            LocalDateTime endTime = LocalDateTime.now();

            // 6. Write consolidated FINAL response file
            FinalFileResponse finalResponse = responseFileWriterService.writeFinalResponse(
                    responseFolder, relativePathStr, batchResponses, totalRecords, startTime, endTime);

            log.info("Final response written: {}", finalResponse.getFinalResponseFilePath());

            // 7. Route processed file based on outcome
            long failedBatches = batchResponses.stream()
                    .filter(b -> b.getStatus() == BatchApiResponse.BatchStatus.FAILED)
                    .count();

            FileProcessingResult.Status fileStatus;
            if (failedBatches == 0) {
                fileManagementService.moveInboundToCompleted(inboundFile, processingFile, relativePathStr);
                fileStatus = FileProcessingResult.Status.SUCCESS;
            } else {
                fileManagementService.moveToError(inboundFile, processingFile,
                        failedBatches + "/" + totalBatches + " batches failed");
                fileStatus = failedBatches == totalBatches
                        ? FileProcessingResult.Status.FAILED
                        : FileProcessingResult.Status.PARTIAL_SUCCESS;
            }

            long durationMs = java.time.Duration.between(startTime, endTime).toMillis();
            log.info("=== File {} done. Status={}, {}ms ===", relativePathStr, fileStatus, durationMs);

            return FileProcessingResult.builder()
                    .fileName(relativePathStr)
                    .status(fileStatus)
                    .totalRecords(totalRecords)
                    .processedRecords(finalResponse.getProcessedRecords())
                    .failedBatches((int) failedBatches)
                    .startTime(startTime)
                    .endTime(endTime)
                    .durationMs(durationMs)
                    .finalResponseFilePath(finalResponse.getFinalResponseFilePath())
                    .build();

        } catch (FileReadException e) {
            log.error("Failed to read file {}: {}", fileName, e.getMessage(), e);
            if (processingFile != null) {
                fileManagementService.moveToError(inboundFile, processingFile, "Read error: " + e.getMessage());
            } else {
                fileManagementService.moveToError(inboundFile, inboundFile, "Read error: " + e.getMessage());
            }
            writeErrorFinalResponse(responseFolder, relativePathStr, startTime, e.getMessage());
            return buildFailedResult(relativePathStr, startTime, e.getMessage());

        } catch (IOException e) {
            log.error("IO error while handling file {}: {}", fileName, e.getMessage(), e);
            if (processingFile != null) {
                fileManagementService.moveToError(inboundFile, processingFile, "IO error: " + e.getMessage());
            } else {
                fileManagementService.moveToError(inboundFile, inboundFile, "IO error: " + e.getMessage());
            }
            writeErrorFinalResponse(responseFolder, relativePathStr, startTime, e.getMessage());
            return buildFailedResult(relativePathStr, startTime, e.getMessage());

        } catch (Exception e) {
            log.error("Unexpected error processing {}: {}", fileName, e.getMessage(), e);
            if (processingFile != null) {
                fileManagementService.moveToError(inboundFile, processingFile, "Unexpected: " + e.getMessage());
            } else {
                fileManagementService.moveToError(inboundFile, inboundFile, "Unexpected: " + e.getMessage());
            }
            writeErrorFinalResponse(responseFolder, relativePathStr, startTime, e.getMessage());
            return buildFailedResult(relativePathStr, startTime, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // JSON parsing: message envelope (message.requestData.defaultEventList) or legacy format
    // -------------------------------------------------------------------------

    /**
     * Read and prepare records. Supports:
     * - message envelope: { "message": { "requestData": { "defaultEventList": [...] } } }
     *   → deduplicates by loan_id, keeps first occurrence
     * - legacy: root array, or object with "data"/"records"/"items" array
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readAndPrepareRecords(Path file) throws FileReadException {
        try {
            // For files >1MB, use streaming to handle message envelope reliably (readTree can OOM or fail on large files)
            long sizeBytes = Files.size(file);
            if (sizeBytes > 1024 * 1024) {
                log.debug("File {} MB, using streaming parser", sizeBytes / (1024 * 1024));
                return streamReadJsonArray(file);
            }

            JsonNode root = objectMapper.readTree(file.toFile());

            // Check for message envelope format: message.requestData.defaultEventList
            if (root.isObject()) {
                JsonNode messageNode = root.get("message");
                if (messageNode != null && messageNode.isObject()) {
                    JsonNode requestDataNode = messageNode.get("requestData");
                    if (requestDataNode != null && requestDataNode.isObject()) {
                        JsonNode defaultEventListNode = requestDataNode.get("defaultEventList");
                        if (defaultEventListNode != null && defaultEventListNode.isArray()) {
                            List<Map<String, Object>> raw = objectMapper.convertValue(
                                    defaultEventListNode, new TypeReference<List<Map<String, Object>>>() {});
                            List<Map<String, Object>> deduplicated = deduplicateByLoanId(raw);
                            log.info("Message envelope format: {} records -> {} unique after deduplication",
                                    raw.size(), deduplicated.size());
                            return deduplicated;
                        }
                        if (requestDataNode.has("defaultEventList")) {
                            return new ArrayList<>();
                        }
                    }
                }
            }

            // Fall back to legacy stream parsing (root array or data/records/items)
            return streamReadJsonArray(file);
        } catch (IOException e) {
            throw new FileReadException("Failed to parse JSON: " + file.getFileName(), e);
        }
    }

    /**
     * Stream through message.requestData.defaultEventList, reading each array element one-by-one.
     * Avoids loading the entire array into memory (supports 100MB+ files).
     * Parser must be positioned at START_OBJECT (the message value).
     * Returns count of records read, or -1 if defaultEventList not found.
     */
    @SuppressWarnings("unchecked")
    private int streamMessageDefaultEventList(JsonParser parser, List<Map<String, Object>> records)
            throws IOException {
        // We're at START_OBJECT of message; iterate its fields
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String fieldName = parser.getCurrentName();
            if (fieldName == null) continue;
            parser.nextToken();
            if (fieldName.equals("requestData") && parser.currentToken() == JsonToken.START_OBJECT) {
                // Descend into requestData
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    String reqField = parser.getCurrentName();
                    if (reqField == null) continue;
                    parser.nextToken();
                    if (reqField.equals("defaultEventList") && parser.currentToken() == JsonToken.START_ARRAY) {
                        int count = 0;
                        while (parser.nextToken() != JsonToken.END_ARRAY) {
                            records.add(objectMapper.readValue(parser, Map.class));
                            count++;
                        }
                        return count;
                    }
                    parser.skipChildren();
                }
                return -1;
            }
            parser.skipChildren();
        }
        return -1;
    }

    /**
     * Deduplicate defaultEventList by loan_id (keeps first occurrence).
     */
    private List<Map<String, Object>> deduplicateByLoanId(List<Map<String, Object>> records) {
        List<Map<String, Object>> result = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();

        for (Map<String, Object> record : records) {
            Object loanIdObj = record.get("loan_id");
            String key = loanIdObj != null ? loanIdObj.toString() : "";
            if (seen.add(key)) {
                result.add(record);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> streamReadJsonArray(Path file) throws FileReadException {
        List<Map<String, Object>> records = new ArrayList<>();

        try (JsonParser parser = objectMapper.getFactory().createParser(file.toFile())) {
            JsonToken firstToken = parser.nextToken();

            if (firstToken == JsonToken.START_ARRAY) {
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    records.add(objectMapper.readValue(parser, Map.class));
                }
            } else if (firstToken == JsonToken.START_OBJECT) {
                boolean found = false;
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    String fieldName = parser.getCurrentName();
                    parser.nextToken();
                    if ((fieldName.equalsIgnoreCase("data")
                            || fieldName.equalsIgnoreCase("records")
                            || fieldName.equalsIgnoreCase("items"))
                            && parser.currentToken() == JsonToken.START_ARRAY) {
                        found = true;
                        while (parser.nextToken() != JsonToken.END_ARRAY) {
                            records.add(objectMapper.readValue(parser, Map.class));
                        }
                    } else if (fieldName != null && fieldName.equals("message")
                            && parser.currentToken() == JsonToken.START_OBJECT) {
                        // Message envelope: stream defaultEventList elements one-by-one (no full readTree = supports 100MB+)
                        int rawCount = streamMessageDefaultEventList(parser, records);
                        if (rawCount >= 0) {
                            found = true;
                            List<Map<String, Object>> deduped = deduplicateByLoanId(records);
                            records.clear();
                            records.addAll(deduped);
                            log.info("Message envelope (stream): {} records -> {} unique after deduplication",
                                    rawCount, records.size());
                        }
                    } else {
                        parser.skipChildren();
                    }
                }
                if (!found) {
                    throw new FileReadException(
                            "JSON object has no 'data', 'records', 'items', or 'message.requestData.defaultEventList' array", null);
                }
            } else {
                throw new FileReadException(
                        "Unexpected JSON structure — got: " + firstToken, null);
            }
        } catch (IOException e) {
            throw new FileReadException("Failed to parse JSON: " + file.getFileName(), e);
        }

        return records;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void writeErrorFinalResponse(Path responseFolder, String fileName,
                                          LocalDateTime startTime, String error) {
        if (responseFolder == null) return;
        try {
            BatchApiResponse errorBatch = BatchApiResponse.builder()
                    .sourceFile(fileName)
                    .batchNumber(0)
                    .totalBatches(0)
                    .recordCount(0)
                    .status(BatchApiResponse.BatchStatus.FAILED)
                    .errorMessage(error)
                    .requestTime(startTime)
                    .responseTime(LocalDateTime.now())
                    .build();

            responseFileWriterService.writeFinalResponse(
                    responseFolder, fileName,
                    List.of(errorBatch), 0, startTime, LocalDateTime.now());
        } catch (Exception ex) {
            log.warn("Could not write error final response for {}", fileName, ex);
        }
    }

    private FileProcessingResult buildFailedResult(String fileName,
                                                    LocalDateTime startTime, String error) {
        LocalDateTime endTime = LocalDateTime.now();
        return FileProcessingResult.builder()
                .fileName(fileName)
                .status(FileProcessingResult.Status.FAILED)
                .totalRecords(0)
                .processedRecords(0)
                .failedBatches(0)
                .errorMessage(error)
                .startTime(startTime)
                .endTime(endTime)
                .durationMs(java.time.Duration.between(startTime, endTime).toMillis())
                .build();
    }
}
