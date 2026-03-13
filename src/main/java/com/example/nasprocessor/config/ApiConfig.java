package com.example.nasprocessor.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Data
@Configuration
@ConfigurationProperties(prefix = "api")
public class ApiConfig {

    private static final String PLACEHOLDER_HOST = "your-api-endpoint.com";
    // Review trigger (TEST-123): this line intentionally exceeds 120 characters so the code review agent posts an inline PR comment on this file.

    private String baseUrl;
    private String endpoint;
    private String aboutVersionIdentifier = "v1.0.0";
    private String requestingSystem = "nasFileProcessor";
    private int timeoutSeconds = 30;
    private int maxRetries = 3;
    private long retryDelayMs = 2000;
    private String authToken;

    @PostConstruct
    public void validateBaseUrl() {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(
                "api.base-url is not set. Set api.base-url (e.g. http://localhost:8081) in application.yml or API_BASE_URL env var.");
        }
        if (baseUrl.contains(PLACEHOLDER_HOST)) {
            throw new IllegalStateException(
                "api.base-url is still the placeholder '" + PLACEHOLDER_HOST + "'. " +
                "Set api.base-url to your process API (e.g. http://localhost:8081) in application.yml or API_BASE_URL env var.");
        }
    }

    @Bean
    public WebClient webClient(WebClient.Builder builder) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutSeconds * 1000)
                .responseTimeout(Duration.ofSeconds(timeoutSeconds))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(timeoutSeconds, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(timeoutSeconds, TimeUnit.SECONDS)));

        return builder
                .baseUrl(baseUrl != null ? baseUrl : "")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
