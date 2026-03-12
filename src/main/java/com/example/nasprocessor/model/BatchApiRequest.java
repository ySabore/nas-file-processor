package com.example.nasprocessor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Payload sent to the external API per batch.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchApiRequest {

    private String sourceFile;
    private int batchNumber;
    private int totalBatches;
    private int batchSize;
    private List<Map<String, Object>> records;
}
