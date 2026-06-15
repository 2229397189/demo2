package com.agi.assistant.model.dto;

import lombok.Data;

@Data
public class EvaluationTaskRequest {

    private String name;

    private String datasetId;

    private String retrievalStrategy;

    private String modelId;
}
