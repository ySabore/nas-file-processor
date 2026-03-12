package com.example.nasprocessor.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end automation tests: full application context, real NAS folders (temp dir),
 * and MockWebServer as the external process API. Verifies trigger → process → complete flow.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("e2e")
class E2eAutomationTest {

    private static MockWebServer mockApiServer;
    private static Path e2eBaseDir;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @DynamicPropertySource
    static void configureE2e(DynamicPropertyRegistry registry) throws IOException {
        mockApiServer = new MockWebServer();
        mockApiServer.start();

        e2eBaseDir = Files.createTempDirectory("e2e-nas-");
        Files.createDirectories(e2eBaseDir.resolve("inbound"));
        Files.createDirectories(e2eBaseDir.resolve("processing"));
        Files.createDirectories(e2eBaseDir.resolve("completed"));
        Files.createDirectories(e2eBaseDir.resolve("error"));
        Files.createDirectories(e2eBaseDir.resolve("responses"));

        registry.add("api.base-url", () -> mockApiServer.url("/").toString());
        registry.add("nas.folders.inbound", () -> e2eBaseDir.resolve("inbound").toString());
        registry.add("nas.folders.processing", () -> e2eBaseDir.resolve("processing").toString());
        registry.add("nas.folders.completed", () -> e2eBaseDir.resolve("completed").toString());
        registry.add("nas.folders.error", () -> e2eBaseDir.resolve("error").toString());
        registry.add("nas.folders.responses", () -> e2eBaseDir.resolve("responses").toString());
    }

    @AfterAll
    static void tearDown() throws IOException {
        if (mockApiServer != null) {
            mockApiServer.shutdown();
        }
        if (e2eBaseDir != null && Files.exists(e2eBaseDir)) {
            deleteRecursively(e2eBaseDir);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                stream.forEach(p -> {
                    try {
                        deleteRecursively(p);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
        Files.deleteIfExists(path);
    }

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void enqueueMockApiSuccess() {
        String body = """
            {"message":{"aboutVersions":{},"responseData":{"defaultEventList":[]}}}
            """;
        mockApiServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(body)
                .addHeader("Content-Type", "application/json"));
    }

    @Test
    void health_returnsUp() throws Exception {
        mockMvc.perform(get("/api/processor/health").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("nas-file-processor"));
    }

    @Test
    void status_returnsRunningFlag() throws Exception {
        mockMvc.perform(get("/api/processor/status").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running").isBoolean())
                .andExpect(jsonPath("$.timestamp").isString());
    }

    @Test
    void trigger_withInboundFile_processesFileAndReturnsSuccess() throws Exception {
        Path inboundFile = e2eBaseDir.resolve("inbound").resolve("automation_test.json");
        List<Map<String, Object>> records = List.of(
                Map.of("loan_id", "L1", "amount", 1000),
                Map.of("loan_id", "L2", "amount", 2000)
        );
        MAPPER.writeValue(inboundFile.toFile(), records);

        ResultActions result = mockMvc.perform(
                post("/api/processor/trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON));

        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.filesFound").value(1))
                .andExpect(jsonPath("$.succeeded").value(1))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.timestamp").isString())
                .andExpect(jsonPath("$.details").exists());

        // File should be moved to completed
        Path completedDir = e2eBaseDir.resolve("completed");
        try (Stream<Path> stream = Files.list(completedDir)) {
            long jsonCount = stream
                    .filter(p -> p.getFileName().toString().endsWith(".json") && !p.getFileName().toString().endsWith(".error.txt"))
                    .count();
            assertFalse(jsonCount < 1, "Expected at least one JSON file in completed folder: " + completedDir);
        }
    }

    @Test
    void trigger_whenNoFiles_returnsSuccessWithZeroFiles() throws Exception {
        // Ensure no JSON in inbound (only run this after cleaning or use a fresh dir - we use fresh dir per context)
        mockMvc.perform(
                post("/api/processor/trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.filesFound").value(0));
    }

    @Test
    void trigger_thenStatus_reflectsIdleAfterCompletion() throws Exception {
        mockMvc.perform(post("/api/processor/trigger").contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/processor/status").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(false));
    }
}
