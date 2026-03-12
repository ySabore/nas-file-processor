package com.example.nasprocessor.contract

import com.example.nasprocessor.controller.ProcessorController
import com.example.nasprocessor.model.FileProcessingResult
import com.example.nasprocessor.service.ArchiveService
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
 * Contract tests for /api/processor REST API.
 * Verifies HTTP status codes, response content-type, and required JSON fields for all endpoints.
 */
@WebMvcTest(ProcessorController)
class ProcessorApiContractSpec extends Specification {

    @Autowired
    MockMvc mockMvc

    @SpringBean
    FileOrchestrationService orchestrationService = Mock()

    @SpringBean
    ArchiveService archiveService = Mock()

    def "GET /api/processor/health returns 200 and contract body"() {
        when:
        ResultActions result = mockMvc.perform(get("/api/processor/health").accept(MediaType.APPLICATION_JSON))

        then:
        result.andExpect(status().isOk())
        result.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        def body = result.andReturn().response.contentAsString
        body.contains('"status"')
        body.contains('"UP"')
        body.contains('"service"')
        body.contains('"nas-file-processor"')
        body.contains('"timestamp"')
    }

    def "GET /api/processor/status returns 200 and contract body with running and timestamp"() {
        when:
        orchestrationService.isRunning() >> false
        ResultActions result = mockMvc.perform(get("/api/processor/status").accept(MediaType.APPLICATION_JSON))

        then:
        result.andExpect(status().isOk())
        result.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        def body = result.andReturn().response.contentAsString
        body.contains('"running"')
        body.contains('"timestamp"')
    }

    def "POST /api/processor/trigger when not running returns 200 and contract body"() {
        when:
        orchestrationService.isRunning() >> false
        orchestrationService.runProcessingCycle() >> []
        ResultActions result = mockMvc.perform(
                post("/api/processor/trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON))

        then:
        result.andExpect(status().isOk())
        result.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        def body = result.andReturn().response.contentAsString
        body.contains('"status"')
        body.contains('"SUCCESS"')
        body.contains('"filesFound"')
        body.contains('"succeeded"')
        body.contains('"partial"')
        body.contains('"failed"')
        body.contains('"timestamp"')
        body.contains('"details"')
    }

    def "POST /api/processor/trigger when already running returns 409 and contract body"() {
        when:
        orchestrationService.isRunning() >> true
        ResultActions result = mockMvc.perform(
                post("/api/processor/trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON))

        then:
        result.andExpect(status().isConflict())
        result.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        def body = result.andReturn().response.contentAsString
        body.contains('"status"')
        body.contains('"SKIPPED"')
        body.contains('"message"')
        body.contains('"timestamp"')
    }

    def "POST /api/processor/trigger when cycle has failures returns 207 and contract body"() {
        when:
        orchestrationService.isRunning() >> false
        orchestrationService.runProcessingCycle() >> [
                FileProcessingResult.builder().fileName("a.json").status(FileProcessingResult.Status.FAILED).build()
        ]
        ResultActions result = mockMvc.perform(
                post("/api/processor/trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON))

        then:
        result.andExpect(status().isMultiStatus())
        result.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        def body = result.andReturn().response.contentAsString
        body.contains('"status"')
        body.contains('"COMPLETED_WITH_ERRORS"')
        body.contains('"failed"')
        body.contains('"timestamp"')
        body.contains('"details"')
    }

    def "POST /api/processor/archive when enabled returns 200 and contract body"() {
        when:
        archiveService.runArchive() >> ArchiveService.ArchiveResult.success(5, 0, 5)
        ResultActions result = mockMvc.perform(
                post("/api/processor/archive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON))

        then:
        result.andExpect(status().isOk())
        result.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        def body = result.andReturn().response.contentAsString
        body.contains('"status"')
        body.contains('"SUCCESS"')
        body.contains('"ran"')
        body.contains('"completedCount"')
        body.contains('"errorCount"')
        body.contains('"responsesCount"')
        body.contains('"timestamp"')
    }
}
