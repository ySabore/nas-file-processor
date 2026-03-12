package com.example.nasprocessor.contract;

import com.example.nasprocessor.controller.ProcessorController;
import com.example.nasprocessor.service.FileOrchestrationService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.mockMvc;

/**
 * Base for contract: POST /api/processor/archive when disabled → 503 DISABLED.
 * No ArchiveService bean so controller receives null and returns 503.
 */
@WebMvcTest(ProcessorController.class)
public abstract class ArchiveDisabledBase {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    FileOrchestrationService orchestrationService;

    @BeforeEach
    void setUp() {
        mockMvc(mockMvc);
        org.mockito.Mockito.when(orchestrationService.isRunning()).thenReturn(false);
    }
}
