package com.example.nasprocessor.controller;

import com.example.nasprocessor.model.FileProcessingResult;
import com.example.nasprocessor.service.FileOrchestrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ProcessorControllerTest {

    private FileOrchestrationService orchestrationService;
    private ProcessorController controller;

    @BeforeEach
    void setUp() {
        orchestrationService = mock(FileOrchestrationService.class);
        controller = new ProcessorController(orchestrationService);
    }

    @Test
    void trigger_whenAlreadyRunning_shouldReturn409() {
        when(orchestrationService.isRunning()).thenReturn(true);

        var response = controller.trigger();

        assertThat(response.getStatusCodeValue()).isEqualTo(409);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("status")).isEqualTo("SKIPPED");
        verify(orchestrationService, never()).runProcessingCycle();
    }

    @Test
    void trigger_whenAllSuccess_shouldReturn200AndSuccessStatus() {
        when(orchestrationService.isRunning()).thenReturn(false);

        FileProcessingResult ok = FileProcessingResult.builder()
                .fileName("a.json")
                .status(FileProcessingResult.Status.SUCCESS)
                .build();
        FileProcessingResult partial = FileProcessingResult.builder()
                .fileName("b.json")
                .status(FileProcessingResult.Status.PARTIAL_SUCCESS)
                .build();
        when(orchestrationService.runProcessingCycle()).thenReturn(List.of(ok, partial));

        var response = controller.trigger();

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("status")).isEqualTo("SUCCESS");
        assertThat(body.get("filesFound")).isEqualTo(2);
        assertThat(body.get("succeeded")).isEqualTo(1L);
        assertThat(body.get("partial")).isEqualTo(1L);
        assertThat(body.get("failed")).isEqualTo(0L);
    }

    @Test
    void trigger_whenAnyFailed_shouldReturn207AndCompletedWithErrors() {
        when(orchestrationService.isRunning()).thenReturn(false);

        FileProcessingResult failed = FileProcessingResult.builder()
                .fileName("bad.json")
                .status(FileProcessingResult.Status.FAILED)
                .errorMessage("boom")
                .build();
        when(orchestrationService.runProcessingCycle()).thenReturn(List.of(failed));

        var response = controller.trigger();

        assertThat(response.getStatusCodeValue()).isEqualTo(207);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("status")).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(body.get("failed")).isEqualTo(1L);
    }

    @Test
    void status_shouldReturnRunningFlag() {
        when(orchestrationService.isRunning()).thenReturn(true);

        var response = controller.status();

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("running")).isEqualTo(true);
    }

    @Test
    void health_shouldAlwaysReturnUp() {
        var response = controller.health();

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        Map<String, String> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("status")).isEqualTo("UP");
        assertThat(body.get("service")).isEqualTo("nas-file-processor");
    }
}

