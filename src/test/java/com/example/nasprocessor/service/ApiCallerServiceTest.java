package com.example.nasprocessor.service;

import com.example.nasprocessor.config.ApiConfig;
import com.example.nasprocessor.model.BatchApiRequest;
import com.example.nasprocessor.model.BatchApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ApiCallerServiceTest {

    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void sendBatch_successfulResponse_shouldReturnSuccessStatus() throws Exception {
        // Arrange mock downstream API
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("[{\"status\":\"ok\"}]")
                .addHeader("Content-Type", "application/json"));

        ApiConfig apiConfig = new ApiConfig();
        apiConfig.setBaseUrl(server.url("/").toString());
        apiConfig.setEndpoint("/api/v1/process");
        apiConfig.setAuthToken("dummy-token");
        apiConfig.setAboutVersionIdentifier("v1.0.0");
        apiConfig.setRequestingSystem("nasFileProcessor");
        apiConfig.setTimeoutSeconds(5);
        apiConfig.setMaxRetries(1);
        apiConfig.setRetryDelayMs(10);
        apiConfig.validateBaseUrl();

        WebClient webClient = WebClient.builder()
                .baseUrl(apiConfig.getBaseUrl())
                .build();

        ApiCallerService service = new ApiCallerService(webClient, apiConfig);

        BatchApiRequest request = BatchApiRequest.builder()
                .sourceFile("test.json")
                .batchNumber(1)
                .totalBatches(1)
                .batchSize(2)
                .records(List.of(
                        Map.of("loan_id", "LN-1"),
                        Map.of("loan_id", "LN-2")
                ))
                .build();

        // Act
        BatchApiResponse response = service.sendBatch(request);

        // Assert
        assertThat(response.getStatus()).isEqualTo(BatchApiResponse.BatchStatus.SUCCESS);
        assertThat(response.getHttpStatusCode()).isEqualTo(200);
        assertThat(response.getApiResponseBody()).contains("ok");

        var recorded = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getPath()).isEqualTo("/api/v1/process");

        // Body should be message envelope with requestData.defaultEventList
        String body = recorded.getBody().readUtf8();
        var tree = new ObjectMapper().readTree(body);
        assertThat(tree.has("message")).isTrue();
        assertThat(tree.get("message").has("aboutVersions")).isTrue();
        assertThat(tree.get("message").has("requestData")).isTrue();
        var defaultEventList = tree.get("message").get("requestData").get("defaultEventList");
        assertThat(defaultEventList).isNotNull();
        assertThat(defaultEventList.isArray()).isTrue();
        assertThat(defaultEventList).hasSize(2);
    }
}

