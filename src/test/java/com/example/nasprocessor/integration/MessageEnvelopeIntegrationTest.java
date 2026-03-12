package com.example.nasprocessor.integration;

import com.example.nasprocessor.config.ApiConfig;
import com.example.nasprocessor.config.JacksonConfig;
import com.example.nasprocessor.config.NasProperties;
import com.example.nasprocessor.model.FileProcessingResult;
import com.example.nasprocessor.service.ApiCallerService;
import com.example.nasprocessor.service.FileManagementService;
import com.example.nasprocessor.service.JsonFileProcessorService;
import com.example.nasprocessor.service.ResponseFileWriterService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: full processing flow with MockWebServer.
 * Verifies request envelope, final response envelope, and apiResponseBody format.
 */
class MessageEnvelopeIntegrationTest {

    @TempDir
    Path tempDir;

    private MockWebServer server;
    private Path inboundDir;
    private Path processingDir;
    private Path completedDir;
    private Path errorDir;
    private Path responsesDir;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        inboundDir = tempDir.resolve("inbound");
        processingDir = tempDir.resolve("processing");
        completedDir = tempDir.resolve("completed");
        errorDir = tempDir.resolve("error");
        responsesDir = tempDir.resolve("responses");

        Files.createDirectories(inboundDir);
        Files.createDirectories(processingDir);
        Files.createDirectories(completedDir);
        Files.createDirectories(errorDir);
        Files.createDirectories(responsesDir);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void processFile_shouldSendEnvelopeRequest_andProduceEnvelopeFinalResponse_andPreserveApiResponseBody() throws Exception {
        // Mock downstream API: accept envelope, return response in envelope format
        String mockApiResponseBody = """
            {
              "message": {
                "aboutVersions": {
                  "aboutVersion": {
                    "createdDateTime": "2026-03-05",
                    "aboutVersionIdentifier": "v1.0.0",
                    "requestingSystem": "processApi"
                  }
                },
                "responseData": {
                  "defaultEventList": [
                    {"loan_id": "LN-1", "status": "processed"},
                    {"loan_id": "LN-2", "status": "processed"}
                  ]
                }
              }
            }
            """;

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(mockApiResponseBody)
                .addHeader("Content-Type", "application/json"));

        // Config
        ApiConfig apiConfig = new ApiConfig();
        apiConfig.setBaseUrl(server.url("/").toString());
        apiConfig.setEndpoint("/api/v1/process");
        apiConfig.setAuthToken("test-token");
        apiConfig.setAboutVersionIdentifier("v1.0.0");
        apiConfig.setRequestingSystem("nasFileProcessor");
        apiConfig.setTimeoutSeconds(5);
        apiConfig.setMaxRetries(1);
        apiConfig.setRetryDelayMs(10);
        apiConfig.validateBaseUrl();

        NasProperties nasProps = new NasProperties();
        nasProps.setBatchSize(100);
        nasProps.getFolders().setInbound(inboundDir.toString());
        nasProps.getFolders().setProcessing(processingDir.toString());
        nasProps.getFolders().setCompleted(completedDir.toString());
        nasProps.getFolders().setError(errorDir.toString());
        nasProps.setUseLockFile(false);

        ObjectMapper mapper = new JacksonConfig().objectMapper();
        WebClient webClient = WebClient.builder().baseUrl(apiConfig.getBaseUrl()).build();

        ApiCallerService apiCallerService = new ApiCallerService(webClient, apiConfig);
        FileManagementService fileManagementService = new FileManagementService(nasProps);
        ResponseFileWriterService responseFileWriterService =
                new ResponseFileWriterService(mapper, apiConfig);

        // Inject responses folder via reflection
        var field = ResponseFileWriterService.class.getDeclaredField("responsesBaseFolder");
        field.setAccessible(true);
        field.set(responseFileWriterService, responsesDir.toString());

        JsonFileProcessorService processor = new JsonFileProcessorService(
                nasProps, apiCallerService, Executors.newFixedThreadPool(5),
                fileManagementService, responseFileWriterService, mapper);

        // Create inbound file
        List<Map<String, Object>> records = List.of(
                Map.of("loan_id", "LN-1", "servicer_id", "SVC-001"),
                Map.of("loan_id", "LN-2", "servicer_id", "SVC-002")
        );
        Path inboundFile = inboundDir.resolve("test_batch.json");
        mapper.writeValue(inboundFile.toFile(), records);

        // Act
        var result = processor.processFile(inboundFile);

        // Assert outcome
        assertThat(result.getStatus()).isEqualTo(FileProcessingResult.Status.SUCCESS);
        assertThat(result.getTotalRecords()).isEqualTo(2);
        assertThat(result.getProcessedRecords()).isEqualTo(2);

        // 1. Verify REQUEST body has message envelope
        RecordedRequest recordedRequest = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(recordedRequest).isNotNull();
        String requestBody = recordedRequest.getBody().readUtf8();
        JsonNode reqTree = mapper.readTree(requestBody);
        assertThat(reqTree.has("message")).isTrue();
        assertThat(reqTree.get("message").has("aboutVersions")).isTrue();
        assertThat(reqTree.get("message").get("aboutVersions").get("aboutVersion")
                .get("requestingSystem").asText()).isEqualTo("nasFileProcessor");
        assertThat(reqTree.get("message").has("requestData")).isTrue();
        JsonNode defaultEventList = reqTree.get("message").get("requestData").get("defaultEventList");
        assertThat(defaultEventList).isNotNull();
        assertThat(defaultEventList.isArray()).isTrue();
        assertThat(defaultEventList).hasSize(2);

        // 2. Find and verify FINAL.json has message envelope
        Path finalFile = Files.walk(responsesDir)
                .filter(p -> p.getFileName().toString().endsWith(".FINAL.json"))
                .findFirst()
                .orElseThrow();
        JsonNode finalTree = mapper.readTree(finalFile.toFile());
        assertThat(finalTree.has("message")).isTrue();
        assertThat(finalTree.get("message").has("aboutVersions")).isTrue();
        assertThat(finalTree.get("message").has("responseData")).isTrue();
        JsonNode responseData = finalTree.get("message").get("responseData");
        assertThat(responseData.get("sourceFile").asText()).isEqualTo("test_batch.json");
        assertThat(responseData.get("overallStatus").asText()).isEqualTo("SUCCESS");

        // 3. Verify apiResponseBody in batchResponses has the envelope format (from downstream API)
        JsonNode batchResponses = responseData.get("batchResponses");
        assertThat(batchResponses).isNotNull();
        assertThat(batchResponses.isArray()).isTrue();
        assertThat(batchResponses).hasSize(1);
        String apiResponseBody = batchResponses.get(0).get("apiResponseBody").asText();
        JsonNode apiBodyTree = mapper.readTree(apiResponseBody);
        assertThat(apiBodyTree.has("message")).isTrue();
        assertThat(apiBodyTree.get("message").has("responseData")).isTrue();
        assertThat(apiBodyTree.get("message").get("responseData").has("defaultEventList")).isTrue();
    }

}
