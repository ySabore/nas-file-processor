package com.example.nasprocessor.cucumber;

import com.example.nasprocessor.controller.ProcessorController;
import com.example.nasprocessor.model.FileProcessingResult;
import com.example.nasprocessor.service.ArchiveService;
import com.example.nasprocessor.service.FileOrchestrationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step definitions for Processor REST API feature files.
 * Uses standalone MockMvc (no Spring context).
 */
public class ProcessorApiSteps {

    private static MockMvc mockMvc;
    private static FileOrchestrationService orchestrationService;
    private static ArchiveService archiveService;

    private int lastStatus;
    private String lastResponseBody;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Before
    public void setUp() {
        if (mockMvc == null) {
            orchestrationService = mock(FileOrchestrationService.class);
            archiveService = mock(ArchiveService.class);
            ProcessorController controller = new ProcessorController(orchestrationService);
            try {
                var archiveField = ProcessorController.class.getDeclaredField("archiveService");
                archiveField.setAccessible(true);
                archiveField.set(controller, archiveService);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        }
        reset(orchestrationService);
        reset(archiveService);
    }

    @Given("the processor is idle")
    public void theProcessorIsIdle() {
        when(orchestrationService.isRunning()).thenReturn(false);
    }

    @Given("the processor is busy")
    public void theProcessorIsBusy() {
        when(orchestrationService.isRunning()).thenReturn(true);
    }

    @Given("the processor is {string}")
    public void theProcessorIs(String state) {
        if ("idle".equalsIgnoreCase(state)) {
            theProcessorIsIdle();
        } else if ("busy".equalsIgnoreCase(state)) {
            theProcessorIsBusy();
        } else {
            throw new IllegalArgumentException("Unknown processor state: " + state);
        }
    }

    @Given("the processing cycle will return {string}")
    public void theProcessingCycleWillReturn(String result) {
        if ("no files".equalsIgnoreCase(result) || "".equals(result.trim())) {
            when(orchestrationService.runProcessingCycle()).thenReturn(List.of());
        } else if ("-".equals(result) || "n/a".equalsIgnoreCase(result)) {
            // busy: controller won't call runProcessingCycle
        } else {
            when(orchestrationService.runProcessingCycle()).thenReturn(List.of());
        }
    }

    @Given("the processing cycle will return -")
    public void theProcessingCycleWillReturnNothing() {
        // busy: no need to stub
    }

    @Given("the processing cycle will return no files")
    public void theProcessingCycleWillReturnNoFiles() {
        when(orchestrationService.runProcessingCycle()).thenReturn(List.of());
    }

    @Given("the processing cycle will return one failed file")
    public void theProcessingCycleWillReturnOneFailedFile() {
        FileProcessingResult failed = FileProcessingResult.builder()
                .fileName("a.json")
                .status(FileProcessingResult.Status.FAILED)
                .errorMessage("boom")
                .build();
        when(orchestrationService.runProcessingCycle()).thenReturn(List.of(failed));
    }

    @Given("the archive service is enabled")
    public void theArchiveServiceIsEnabled() {
        when(archiveService.runArchive()).thenReturn(ArchiveService.ArchiveResult.success(0, 0, 0));
    }

    @When("I request GET {string}")
    public void iRequestGet(String path) throws Exception {
        var result = mockMvc.perform(get(path).accept(MediaType.APPLICATION_JSON));
        lastStatus = result.andReturn().getResponse().getStatus();
        lastResponseBody = result.andReturn().getResponse().getContentAsString();
    }

    @When("I request POST {string}")
    public void iRequestPost(String path) throws Exception {
        var result = mockMvc.perform(
                post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{}"));
        lastStatus = result.andReturn().getResponse().getStatus();
        lastResponseBody = result.andReturn().getResponse().getContentAsString();
    }

    @Then("the response status is {int}")
    public void theResponseStatusIs(int expectedStatus) {
        assertThat(lastStatus).isEqualTo(expectedStatus);
    }

    @Then("the response body contains {string} with value {string}")
    public void theResponseBodyContainsWithValue(String key, String expectedValue) throws Exception {
        assertThat(lastResponseBody).isNotBlank();
        Map<?, ?> body = MAPPER.readValue(lastResponseBody, Map.class);
        Object value = body.get(key);
        assertThat(value).isNotNull();
        assertThat(String.valueOf(value)).isEqualTo(expectedValue);
    }

    @Then("the response body contains {string}")
    public void theResponseBodyContains(String key) throws Exception {
        assertThat(lastResponseBody).isNotBlank();
        Map<?, ?> body = MAPPER.readValue(lastResponseBody, Map.class);
        assertThat(body.containsKey(key)).isTrue();
    }
}
