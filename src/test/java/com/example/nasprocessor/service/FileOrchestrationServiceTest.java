package com.example.nasprocessor.service;

import com.example.nasprocessor.model.FileProcessingResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FileOrchestrationServiceTest {

    private FileManagementService fileManagementService;
    private JsonFileProcessorService jsonFileProcessorService;
    private FileOrchestrationService orchestrationService;

    @BeforeEach
    void setUp() {
        fileManagementService = mock(FileManagementService.class);
        jsonFileProcessorService = mock(JsonFileProcessorService.class);
        orchestrationService = new FileOrchestrationService(fileManagementService, jsonFileProcessorService);
    }

    @Test
    void runProcessingCycle_whenNoInboundFiles_shouldReturnEmpty() {
        when(fileManagementService.getInboundFiles()).thenReturn(List.of());

        List<FileProcessingResult> results = orchestrationService.runProcessingCycle();

        assertThat(results).isEmpty();
        assertThat(orchestrationService.isRunning()).isFalse();
        verify(fileManagementService).getInboundFiles();
        verifyNoInteractions(jsonFileProcessorService);
    }

    @Test
    void runProcessingCycle_withFiles_shouldProcessEachAndLog() {
        Path f1 = Path.of("a.json");
        Path f2 = Path.of("b.json");
        when(fileManagementService.getInboundFiles()).thenReturn(List.of(f1, f2));

        FileProcessingResult success = FileProcessingResult.builder()
                .fileName("a.json")
                .status(FileProcessingResult.Status.SUCCESS)
                .processedRecords(10)
                .durationMs(100)
                .build();
        FileProcessingResult failed = FileProcessingResult.builder()
                .fileName("b.json")
                .status(FileProcessingResult.Status.FAILED)
                .errorMessage("boom")
                .build();

        when(jsonFileProcessorService.processFile(f1)).thenReturn(success);
        when(jsonFileProcessorService.processFile(f2)).thenReturn(failed);

        List<FileProcessingResult> results = orchestrationService.runProcessingCycle();

        assertThat(results).containsExactly(success, failed);
        assertThat(orchestrationService.isRunning()).isFalse();
        verify(jsonFileProcessorService).processFile(f1);
        verify(jsonFileProcessorService).processFile(f2);
    }

    @Test
    void runProcessingCycle_whenAlreadyRunning_shouldReturnImmediately() throws Exception {
        // Force running flag to true
        var runningField = FileOrchestrationService.class.getDeclaredField("running");
        runningField.setAccessible(true);
        AtomicBoolean running = (AtomicBoolean) runningField.get(orchestrationService);
        running.set(true);

        List<FileProcessingResult> results = orchestrationService.runProcessingCycle();

        assertThat(results).isEmpty();
        verifyNoInteractions(fileManagementService, jsonFileProcessorService);
    }
}

