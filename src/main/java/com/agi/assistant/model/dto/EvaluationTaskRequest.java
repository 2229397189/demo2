package com.agi.assistant.model.dto;

import lombok.Data;

@Data
public class EvaluationTaskRequest {

    private String name;

    private Long datasetId;

    private String retrievalStrategy;

    private String modelId;
}
