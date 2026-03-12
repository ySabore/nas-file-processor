package com.example.nasprocessor.service;

import com.example.nasprocessor.config.ApiConfig;
import com.example.nasprocessor.exception.ApiCallException;
import com.example.nasprocessor.model.ApiMessageRequest;
import com.example.nasprocessor.model.BatchApiRequest;
import com.example.nasprocessor.model.BatchApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApiCallerService {

    private final WebClient webClient;
    private final ApiConfig apiConfig;

    /**
     * Send a batch to the external API and return a populated BatchApiResponse.
     * Retries on transient failures with exponential backoff.
     */
    @Retryable(
        retryFor = { ApiCallException.class },
        maxAttemptsExpression = "#{@apiConfig.maxRetries}",
        backoff = @Backoff(delayExpression = "#{@apiConfig.retryDelayMs}", multiplier = 2)
    )
    public BatchApiResponse sendBatch(BatchApiRequest batchRequest) {
        String uri = apiConfig.getEndpoint();
        LocalDateTime requestTime = LocalDateTime.now();

        log.info("Sending batch {}/{} for '{}' ({} records) -> {}",
                batchRequest.getBatchNumber(), batchRequest.getTotalBatches(),
                batchRequest.getSourceFile(), batchRequest.getRecords().size(), uri);

        try {
            ApiMessageRequest envelope = ApiMessageRequest.builder()
                    .message(ApiMessageRequest.Message.builder()
                            .aboutVersions(ApiMessageRequest.AboutVersions.builder()
                                    .aboutVersion(ApiMessageRequest.AboutVersion.builder()
                                            .createdDateTime(LocalDateTime.now().toString())
                                            .aboutVersionIdentifier(apiConfig.getAboutVersionIdentifier())
                                            .requestingSystem(apiConfig.getRequestingSystem())
                                            .build())
                                    .build())
                            .requestData(ApiMessageRequest.RequestData.builder()
                                    .defaultEventList(batchRequest.getRecords())
                                    .build())
                            .build())
                    .build();

            ResponseEntity<String> response = webClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(apiConfig.getAuthToken()))
                    .bodyValue(envelope)
                    .retrieve()
                    .toEntity(String.class)
                    .block();

            if (response == null || !response.getStatusCode().is2xxSuccessful()) {
                int code = response != null ? response.getStatusCode().value() : 0;
                String body = response != null ? response.getBody() : "";
                throw new ApiCallException("Non-2xx response: " + code + " | " + body);
            }

            LocalDateTime responseTime = LocalDateTime.now();
            int statusCode = response.getStatusCode().value();

            log.info("Batch {}/{} accepted. HTTP {}",
                    batchRequest.getBatchNumber(), batchRequest.getTotalBatches(), statusCode);

            return BatchApiResponse.builder()
                    .sourceFile(batchRequest.getSourceFile())
                    .batchNumber(batchRequest.getBatchNumber())
                    .totalBatches(batchRequest.getTotalBatches())
                    .recordCount(batchRequest.getRecords().size())
                    .status(BatchApiResponse.BatchStatus.SUCCESS)
                    .httpStatusCode(statusCode)
                    .apiResponseBody(response.getBody())
                    .requestTime(requestTime)
                    .responseTime(responseTime)
                    .durationMs(java.time.Duration.between(requestTime, responseTime).toMillis())
                    .build();

        } catch (WebClientResponseException e) {
            log.error("Client error for batch {}: {} - {}",
                    batchRequest.getBatchNumber(), e.getStatusCode(), e.getResponseBodyAsString());
            throw new ApiCallException("Client error: " + e.getStatusCode() +
                    " body=" + e.getResponseBodyAsString(), e);
        } catch (ApiCallException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Transient error on batch {} (will retry): {}",
                    batchRequest.getBatchNumber(), e.getMessage());
            throw new ApiCallException("Transient error: " + e.getMessage(), e);
        }
    }

    /**
     * Called after all retries are exhausted - returns a FAILED BatchApiResponse
     * so the processor can continue with remaining batches.
     */
    @Recover
    public BatchApiResponse recoverFromApiFailure(ApiCallException ex, BatchApiRequest batchRequest) {
        log.error("All retries exhausted for batch {}/{} of '{}'. Error: {}",
                batchRequest.getBatchNumber(), batchRequest.getTotalBatches(),
                batchRequest.getSourceFile(), ex.getMessage());

        LocalDateTime now = LocalDateTime.now();
        return BatchApiResponse.builder()
                .sourceFile(batchRequest.getSourceFile())
                .batchNumber(batchRequest.getBatchNumber())
                .totalBatches(batchRequest.getTotalBatches())
                .recordCount(batchRequest.getRecords().size())
                .status(BatchApiResponse.BatchStatus.FAILED)
                .httpStatusCode(0)
                .errorMessage(ex.getMessage())
                .requestTime(now)
                .responseTime(now)
                .durationMs(0)
                .build();
    }

}
