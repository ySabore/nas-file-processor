package com.example.nasprocessor.contract;

import com.example.nasprocessor.controller.ProcessorController;
import com.example.nasprocessor.service.ArchiveService;
import com.example.nasprocessor.service.FileOrchestrationService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static io.restassured.module.mockmvc.RestAssuredMockMvc.mockMvc;

/**
 * Base for contract: POST /api/processor/trigger when already running → 409 SKIPPED.
 */
@WebMvcTest(ProcessorController.class)
public abstract class TriggerSkippedBase {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    FileOrchestrationService orchestrationService;

    @MockBean
    ArchiveService archiveService;

    @BeforeEach
    void setUp() {
        mockMvc(mockMvc);
        when(orchestrationService.isRunning()).thenReturn(true);
    }
}
