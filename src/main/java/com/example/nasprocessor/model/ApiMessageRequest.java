package com.example.nasprocessor.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Request envelope for the downstream process API.
 * Wraps records in message.aboutVersions + message.requestData.defaultEventList.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiMessageRequest {

    private Message message;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        private AboutVersions aboutVersions;
        private RequestData requestData;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AboutVersions {
        private AboutVersion aboutVersion;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AboutVersion {
        private String createdDateTime;
        private String aboutVersionIdentifier;
        private String requestingSystem;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RequestData {
        @JsonProperty("defaultEventList")
        private List<Map<String, Object>> defaultEventList;
    }
}
