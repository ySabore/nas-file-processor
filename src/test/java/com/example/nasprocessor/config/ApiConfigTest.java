package com.example.nasprocessor.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiConfigTest {

    @Test
    void validateBaseUrl_withValidUrl_shouldPass() {
        ApiConfig config = new ApiConfig();
        config.setBaseUrl("http://localhost:8081");

        // Should not throw
        config.validateBaseUrl();
    }

    @Test
    void validateBaseUrl_withBlank_shouldThrow() {
        ApiConfig config = new ApiConfig();
        config.setBaseUrl("  ");

        assertThatThrownBy(config::validateBaseUrl)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api.base-url is not set");
    }

    @Test
    void validateBaseUrl_withPlaceholderHost_shouldThrow() {
        ApiConfig config = new ApiConfig();
        config.setBaseUrl("https://your-api-endpoint.com/api/v1/process");

        assertThatThrownBy(config::validateBaseUrl)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder");
    }

    @Test
    void webClient_shouldBuildClientWithBaseUrl() {
        ApiConfig config = new ApiConfig();
        config.setBaseUrl("http://localhost:8081");

        WebClient client = config.webClient(WebClient.builder());
        assertThat(client).isNotNull();
    }
}

