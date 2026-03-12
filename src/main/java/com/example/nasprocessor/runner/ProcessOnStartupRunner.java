package com.example.nasprocessor.runner;

import com.example.nasprocessor.service.FileOrchestrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Runs one processing cycle after the application has started, so any JSON files
 * already in the inbound folder are processed without waiting for the scheduler or a manual trigger.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(1)
@ConditionalOnProperty(name = "processor.run-on-startup", havingValue = "true", matchIfMissing = false)
public class ProcessOnStartupRunner implements ApplicationRunner {

    private final FileOrchestrationService orchestrationService;

    @Override
    public void run(ApplicationArguments args) {
        log.info("Running processing cycle on startup (processor.run-on-startup=true)");
        orchestrationService.runProcessingCycle();
    }
}
