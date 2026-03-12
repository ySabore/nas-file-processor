package com.example.nasprocessor.service;

import com.example.nasprocessor.config.ApiConfig;
import com.example.nasprocessor.model.BatchApiResponse;
import com.example.nasprocessor.model.FinalFileResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseFileWriterServiceTest {

    @TempDir
    Path tempDir;

    private ResponseFileWriterService service;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        ApiConfig apiConfig = new ApiConfig();
        apiConfig.setAboutVersionIdentifier("v1.0.0");
        apiConfig.setRequestingSystem("nasFileProcessor");
        service = new ResponseFileWriterService(mapper, apiConfig);
        // Inject responsesBaseFolder via reflection
        Field field = ResponseFileWriterService.class.getDeclaredField("responsesBaseFolder");
        field.setAccessible(true);
        field.set(service, tempDir.toString());
    }

    @Test
    void createResponseFolder_shouldCreateDirectoryUnderBase() throws IOException {
        Path folder = service.createResponseFolder("orders.json");

        assertThat(Files.exists(folder)).isTrue();
        assertThat(folder.getParent()).isEqualTo(tempDir);
        assertThat(folder.getFileName().toString()).startsWith("orders_");
    }

    @Test
    void createResponseFolder_shouldPreserveStructureForNestedPath() throws IOException {
        Path folder = service.createResponseFolder("folder_a/file.json");

        assertThat(Files.exists(folder)).isTrue();
        assertThat(folder.getParent().getFileName().toString()).isEqualTo("folder_a");
        assertThat(folder.getFileName().toString()).startsWith("file_");
    }

    @Test
    void writeBatchResponse_shouldWriteFileAndSetResponsePath() throws IOException {
        Path folder = Files.createDirectory(tempDir.resolve("resp"));

        BatchApiResponse batch = BatchApiResponse.builder()
                .sourceFile("file.json")
                .batchNumber(1)
                .totalBatches(3)
                .recordCount(2)
                .status(BatchApiResponse.BatchStatus.SUCCESS)
                .build();

        BatchApiResponse result = service.writeBatchResponse(folder, batch);

        assertThat(result.getResponseFilePath()).isNotBlank();
        Path written = Path.of(result.getResponseFilePath());
        assertThat(Files.exists(written)).isTrue();
        assertThat(written.getFileName().toString()).isEqualTo("response.json");
        assertThat(written.getParent().getFileName().toString()).isEqualTo("batch_001_of_003");
    }

    @Test
    void writeFinalResponse_allSuccess_shouldBeSuccess() throws IOException {
        Path folder = Files.createDirectory(tempDir.resolve("final-success"));

        BatchApiResponse b1 = BatchApiResponse.builder()
                .sourceFile("file.json")
                .batchNumber(1)
                .totalBatches(2)
                .recordCount(10)
                .status(BatchApiResponse.BatchStatus.SUCCESS)
                .build();
        BatchApiResponse b2 = BatchApiResponse.builder()
                .sourceFile("file.json")
                .batchNumber(2)
                .totalBatches(2)
                .recordCount(15)
                .status(BatchApiResponse.BatchStatus.SUCCESS)
                .build();

        LocalDateTime start = LocalDateTime.now().minusSeconds(5);
        LocalDateTime end = LocalDateTime.now();

        FinalFileResponse finalResp = service.writeFinalResponse(
                folder, "file.json", List.of(b1, b2), 25, start, end);

        assertThat(finalResp.getOverallStatus()).isEqualTo(FinalFileResponse.OverallStatus.SUCCESS);
        assertThat(finalResp.getProcessedRecords()).isEqualTo(25);
        assertThat(finalResp.getFailedBatches()).isZero();
        assertThat(finalResp.getSummaryMessage())
                .contains("All 2 batches processed successfully")
                .contains("25/25 records sent");

        Path finalFile = Path.of(finalResp.getFinalResponseFilePath());
        assertThat(Files.exists(finalFile)).isTrue();

        // Verify message envelope format
        JsonNode root = mapper.readTree(finalFile.toFile());
        assertThat(root.has("message")).isTrue();
        assertThat(root.get("message").has("aboutVersions")).isTrue();
        assertThat(root.get("message").has("responseData")).isTrue();
        JsonNode responseData = root.get("message").get("responseData");
        assertThat(responseData.get("sourceFile").asText()).isEqualTo("file.json");
        assertThat(responseData.get("overallStatus").asText()).isEqualTo("SUCCESS");
    }

    @Test
    void writeFinalResponse_partialAndFailed_shouldSetAppropriateStatus() throws IOException {
        Path folder = Files.createDirectory(tempDir.resolve("final-mixed"));

        BatchApiResponse success = BatchApiResponse.builder()
                .sourceFile("file.json")
                .batchNumber(1)
                .totalBatches(3)
                .recordCount(10)
                .status(BatchApiResponse.BatchStatus.SUCCESS)
                .build();
        BatchApiResponse failed1 = BatchApiResponse.builder()
                .sourceFile("file.json")
                .batchNumber(2)
                .totalBatches(3)
                .recordCount(0)
                .status(BatchApiResponse.BatchStatus.FAILED)
                .errorMessage("boom")
                .build();
        BatchApiResponse failed2 = BatchApiResponse.builder()
                .sourceFile("file.json")
                .batchNumber(3)
                .totalBatches(3)
                .recordCount(0)
                .status(BatchApiResponse.BatchStatus.FAILED)
                .errorMessage("boom")
                .build();

        LocalDateTime start = LocalDateTime.now().minusSeconds(10);
        LocalDateTime end = LocalDateTime.now();

        FinalFileResponse partial = service.writeFinalResponse(
                folder, "file.json", List.of(success, failed1), 10, start, end);
        assertThat(partial.getOverallStatus()).isEqualTo(FinalFileResponse.OverallStatus.PARTIAL_SUCCESS);
        assertThat(partial.getSummaryMessage())
                .contains("1/2 batches succeeded")
                .contains("10/10 records sent");

        FinalFileResponse failed = service.writeFinalResponse(
                folder, "file.json", List.of(failed1, failed2), 20, start, end);
        assertThat(failed.getOverallStatus()).isEqualTo(FinalFileResponse.OverallStatus.FAILED);
        assertThat(failed.getSummaryMessage())
                .contains("All 2 batches failed")
                .contains("0/20 records sent");
    }
}

