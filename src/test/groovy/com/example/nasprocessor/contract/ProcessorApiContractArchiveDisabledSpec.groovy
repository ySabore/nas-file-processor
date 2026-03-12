package com.example.nasprocessor.contract

import com.example.nasprocessor.controller.ProcessorController
import com.example.nasprocessor.service.FileOrchestrationService
import org.spockframework.spring.SpringBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import spock.lang.Specification

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

/**
 * Contract test for /api/processor/archive when archive is disabled (no ArchiveService bean).
 * Verifies 503 and DISABLED response body.
 */
@WebMvcTest(ProcessorController)
class ProcessorApiContractArchiveDisabledSpec extends Specification {

    @Autowired
    MockMvc mockMvc

    @SpringBean
    FileOrchestrationService orchestrationService = Mock()

    // No ArchiveService bean -> controller receives null, returns 503

    def "POST /api/processor/archive when disabled returns 503 and contract body"() {
        when:
        ResultActions result = mockMvc.perform(
                post("/api/processor/archive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON))

        then:
        result.andExpect(status().isServiceUnavailable())
        result.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        def body = result.andReturn().response.contentAsString
        body.contains('"status"')
        body.contains('"DISABLED"')
        body.contains('"message"')
        body.contains('"timestamp"')
    }
}
