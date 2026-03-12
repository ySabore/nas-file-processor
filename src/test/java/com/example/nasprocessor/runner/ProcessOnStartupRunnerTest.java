package com.example.nasprocessor.runner;

import com.example.nasprocessor.service.FileOrchestrationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ProcessOnStartupRunnerTest {

    @Test
    void run_shouldTriggerProcessingCycle() throws Exception {
        FileOrchestrationService orchestrationService = mock(FileOrchestrationService.class);
        ProcessOnStartupRunner runner = new ProcessOnStartupRunner(orchestrationService);

        runner.run(new DefaultApplicationArguments(new String[0]));

        verify(orchestrationService).runProcessingCycle();
    }
}

