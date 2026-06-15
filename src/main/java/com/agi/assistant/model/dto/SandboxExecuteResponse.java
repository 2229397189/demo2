package com.agi.assistant.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SandboxExecuteResponse {

    private String output;

    private String error;

    private long executionTime;

    private int exitCode;

    private long memoryUsage;

    public SandboxExecuteResponse(String output, String error, long executionTime) {
        this.output = output;
        this.error = error;
        this.executionTime = executionTime;
        this.exitCode = (error != null && !error.isEmpty()) ? 1 : 0;
        this.memoryUsage = 0;
    }
}
