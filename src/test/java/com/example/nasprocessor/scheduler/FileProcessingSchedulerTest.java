package com.example.nasprocessor.scheduler;

import com.example.nasprocessor.service.FileOrchestrationService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class FileProcessingSchedulerTest {

    @Test
    void scheduledRun_shouldDelegateToOrchestrationService() {
        FileOrchestrationService orchestrationService = mock(FileOrchestrationService.class);
        FileProcessingScheduler scheduler = new FileProcessingScheduler(orchestrationService);

        scheduler.scheduledRun();

        verify(orchestrationService).runProcessingCycle();
    }
}

