package com.example.nasprocessor.contract;

import com.example.nasprocessor.controller.ProcessorController;
import com.example.nasprocessor.service.ArchiveService;
import com.example.nasprocessor.service.FileOrchestrationService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static io.restassured.module.mockmvc.RestAssuredMockMvc.mockMvc;

/**
 * Base class for generated Spring Cloud Contract verifier tests (health, status, trigger_success, archive_success).
 * Mocks: running=false, empty cycle, archive success.
 */
@WebMvcTest(ProcessorController.class)
public abstract class ProcessorBase {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    FileOrchestrationService orchestrationService;

    @MockBean
    ArchiveService archiveService;

    @BeforeEach
    void setUp() {
        mockMvc(mockMvc);
        when(orchestrationService.isRunning()).thenReturn(false);
        when(orchestrationService.runProcessingCycle()).thenReturn(List.of());
        when(archiveService.runArchive()).thenReturn(ArchiveService.ArchiveResult.success(0, 0, 0));
    }
}
