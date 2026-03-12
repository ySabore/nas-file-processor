package com.example.nasprocessor.service;

import com.example.nasprocessor.config.NasProperties;
import com.example.nasprocessor.model.BatchApiRequest;
import com.example.nasprocessor.model.BatchApiResponse;
import com.example.nasprocessor.model.FinalFileResponse;
import com.example.nasprocessor.model.FileProcessingResult;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JsonFileProcessorServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private ApiCallerService apiCallerService;

    @Mock
    private FileManagementService fileManagementService;

    @Mock
    private ResponseFileWriterService responseFileWriterService;

    private JsonFileProcessorService service;

    @BeforeEach
    void setUp() throws Exception {
        NasProperties props = new NasProperties();
        props.setBatchSize(100);
        NasProperties.Folders folders = new NasProperties.Folders();
        folders.setInbound(tempDir.toString());
        props.setFolders(folders);
        ObjectMapper mapper = new ObjectMapper();

        // Default behaviour for response writer: just echo inputs into convenient objects
        lenient().when(responseFileWriterService.createResponseFolder(any()))
                .thenReturn(tempDir.resolve("responses"));

        lenient().doNothing().when(responseFileWriterService).writeBatchRequest(any(), anyInt(), anyInt(), any());

        lenient().when(responseFileWriterService.writeBatchResponse(any(), any(BatchApiResponse.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));

        lenient().when(responseFileWriterService.writeFinalResponse(
                any(), any(), any(), anyInt(), any(), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    List<BatchApiResponse> batches = invocation.getArgument(2);
                    int totalRecords = invocation.getArgument(3);
                    LocalDateTime start = invocation.getArgument(4);
                    LocalDateTime end = invocation.getArgument(5);

                    int processedRecords = batches.stream()
                            .filter(b -> b.getStatus() == BatchApiResponse.BatchStatus.SUCCESS)
                            .mapToInt(BatchApiResponse::getRecordCount)
                            .sum();

                    return FinalFileResponse.builder()
                            .overallStatus(FinalFileResponse.OverallStatus.SUCCESS)
                            .processingStartTime(start)
                            .processingEndTime(end)
                            .totalDurationMs(Duration.between(start, end).toMillis())
                            .totalRecords(totalRecords)
                            .totalBatches(batches.size())
                            .successfulBatches(batches.size())
                            .failedBatches(0)
                            .processedRecords(processedRecords)
                            .batchResponses(batches)
                            .build();
                });

        service = new JsonFileProcessorService(
                props, apiCallerService, Executors.newFixedThreadPool(5),
                fileManagementService, responseFileWriterService, mapper);
    }

    @Test
    void processFile_withValidJsonArray_shouldSucceed() throws IOException {
        // Arrange: create a temp JSON file with 250 records
        List<Map<String, Object>> records = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            records.add(Map.of("id", i, "name", "record-" + i));
        }
        Path jsonFile = tempDir.resolve("test.json");
        new ObjectMapper().writeValue(jsonFile.toFile(), records);

        when(fileManagementService.copyToProcessing(jsonFile)).thenReturn(jsonFile);
        when(apiCallerService.sendBatch(any(BatchApiRequest.class))).thenAnswer(invocation -> {
            BatchApiRequest req = invocation.getArgument(0);
            return BatchApiResponse.builder()
                    .sourceFile(req.getSourceFile())
                    .batchNumber(req.getBatchNumber())
                    .totalBatches(req.getTotalBatches())
                    .recordCount(req.getRecords().size())
                    .status(BatchApiResponse.BatchStatus.SUCCESS)
                    .httpStatusCode(200)
                    .apiResponseBody("OK")
                    .build();
        });

        // Act
        FileProcessingResult result = service.processFile(jsonFile);

        // Assert
        assertThat(result.getStatus()).isEqualTo(FileProcessingResult.Status.SUCCESS);
        assertThat(result.getTotalRecords()).isEqualTo(250);
        assertThat(result.getProcessedRecords()).isEqualTo(250);
        // 250 records / 100 batch size = 3 batches
        verify(apiCallerService, times(3)).sendBatch(any());
        verify(fileManagementService).moveInboundToCompleted(eq(jsonFile), eq(jsonFile), eq("test.json"));
    }

    @Test
    void processFile_withInvalidJson_shouldMoveToError() throws IOException {
        // Arrange: create a malformed JSON file
        Path badFile = tempDir.resolve("bad.json");
        Files.writeString(badFile, "{ this is not valid json !!!");

        when(fileManagementService.copyToProcessing(badFile)).thenReturn(badFile);

        // Act
        FileProcessingResult result = service.processFile(badFile);

        // Assert
        assertThat(result.getStatus()).isEqualTo(FileProcessingResult.Status.FAILED);
        verify(fileManagementService).moveToError(eq(badFile), eq(badFile), anyString());
        verify(apiCallerService, never()).sendBatch(any());
    }

    @Test
    void processFile_withObjectWrapper_shouldSucceed() throws IOException {
        // Arrange: JSON wrapped in { "data": [...] }
        Map<String, Object> wrapped = Map.of(
                "meta", Map.of("version", 1),
                "data", List.of(
                        Map.of("id", 1),
                        Map.of("id", 2)
                )
        );
        Path jsonFile = tempDir.resolve("wrapped.json");
        new ObjectMapper().writeValue(jsonFile.toFile(), wrapped);

        when(fileManagementService.copyToProcessing(jsonFile)).thenReturn(jsonFile);
        when(apiCallerService.sendBatch(any(BatchApiRequest.class))).thenAnswer(invocation -> {
            BatchApiRequest req = invocation.getArgument(0);
            return BatchApiResponse.builder()
                    .sourceFile(req.getSourceFile())
                    .batchNumber(req.getBatchNumber())
                    .totalBatches(req.getTotalBatches())
                    .recordCount(req.getRecords().size())
                    .status(BatchApiResponse.BatchStatus.SUCCESS)
                    .httpStatusCode(200)
                    .apiResponseBody("OK")
                    .build();
        });

        // Act
        FileProcessingResult result = service.processFile(jsonFile);

        // Assert
        assertThat(result.getStatus()).isEqualTo(FileProcessingResult.Status.SUCCESS);
        assertThat(result.getTotalRecords()).isEqualTo(2);
    }

    @Test
    void processFile_withMessageEnvelopeFormat_shouldDeduplicateAndSucceed() throws IOException {
        // Arrange: message envelope format with duplicate loan_id
        Map<String, Object> envelope = Map.of(
                "message", Map.of(
                        "aboutVersions", Map.of("aboutVersion", Map.of(
                                "createdDateTime", "2026-02-05",
                                "aboutVersionIdentifier", "v1.0.0",
                                "requestingSystem", "nasFileProcessor")),
                        "requestData", Map.of("defaultEventList", List.of(
                                Map.of("loan_id", "LN-2024-10041", "servicer_id", "SVC-001", "name", "first"),
                                Map.of("loan_id", "LN-2024-10041", "servicer_id", "SVC-001", "name", "duplicate")
                        ))
                )
        );
        Path jsonFile = tempDir.resolve("envelope.json");
        new ObjectMapper().writeValue(jsonFile.toFile(), envelope);

        when(fileManagementService.copyToProcessing(jsonFile)).thenReturn(jsonFile);
        when(apiCallerService.sendBatch(any(BatchApiRequest.class))).thenAnswer(invocation -> {
            BatchApiRequest req = invocation.getArgument(0);
            return BatchApiResponse.builder()
                    .sourceFile(req.getSourceFile())
                    .batchNumber(req.getBatchNumber())
                    .totalBatches(req.getTotalBatches())
                    .recordCount(req.getRecords().size())
                    .status(BatchApiResponse.BatchStatus.SUCCESS)
                    .httpStatusCode(200)
                    .apiResponseBody("OK")
                    .build();
        });

        // Act
        FileProcessingResult result = service.processFile(jsonFile);

        // Assert: deduplicated to 1 record
        assertThat(result.getStatus()).isEqualTo(FileProcessingResult.Status.SUCCESS);
        assertThat(result.getTotalRecords()).isEqualTo(1);
        assertThat(result.getProcessedRecords()).isEqualTo(1);
        verify(apiCallerService, times(1)).sendBatch(argThat(req -> req.getRecords().size() == 1));
    }

    /**
     * Test 100MB message envelope file - verifies streaming parser handles large files without OOM.
     * Creates file via JsonGenerator (streaming write), then processes it.
     */
    @Test
    void processFile_with100MBMessageEnvelope_shouldSucceedWithoutOOM() throws IOException {
        // Create ~100MB file using streaming write (50k records × ~2KB each)
        int recordCount = 50_000;
        String padding = "x".repeat(2000);
        Path jsonFile = tempDir.resolve("large_100mb.json");

        try (JsonGenerator gen = JsonFactory.builder().build()
                .createGenerator(Files.newOutputStream(jsonFile))) {
            gen.writeStartObject();
            gen.writeObjectFieldStart("message");
            gen.writeObjectFieldStart("aboutVersions");
            gen.writeObjectFieldStart("aboutVersion");
            gen.writeStringField("createdDateTime", "2026-02-05");
            gen.writeStringField("aboutVersionIdentifier", "v1.0.0");
            gen.writeStringField("requestingSystem", "nasFileProcessor");
            gen.writeEndObject();
            gen.writeEndObject();
            gen.writeObjectFieldStart("requestData");
            gen.writeArrayFieldStart("defaultEventList");
            for (int i = 0; i < recordCount; i++) {
                gen.writeStartObject();
                gen.writeStringField("loan_id", "LN-" + i);
                gen.writeStringField("servicer_id", "SVC-001");
                gen.writeStringField("data", padding);
                gen.writeEndObject();
            }
            gen.writeEndArray();
            gen.writeEndObject();
            gen.writeEndObject();
            gen.writeEndObject();
        }

        long sizeMB = Files.size(jsonFile) / (1024 * 1024);
        assertThat(sizeMB).isGreaterThanOrEqualTo(95); // ~100MB

        when(fileManagementService.copyToProcessing(jsonFile)).thenReturn(jsonFile);
        when(apiCallerService.sendBatch(any(BatchApiRequest.class))).thenAnswer(invocation -> {
            BatchApiRequest req = invocation.getArgument(0);
            return BatchApiResponse.builder()
                    .sourceFile(req.getSourceFile())
                    .batchNumber(req.getBatchNumber())
                    .totalBatches(req.getTotalBatches())
                    .recordCount(req.getRecords().size())
                    .status(BatchApiResponse.BatchStatus.SUCCESS)
                    .httpStatusCode(200)
                    .apiResponseBody("OK")
                    .build();
        });

        FileProcessingResult result = service.processFile(jsonFile);

        assertThat(result.getStatus()).isEqualTo(FileProcessingResult.Status.SUCCESS);
        assertThat(result.getTotalRecords()).isEqualTo(recordCount);
        assertThat(result.getProcessedRecords()).isEqualTo(recordCount);
        verify(apiCallerService, atLeast(1)).sendBatch(any());
    }
}
