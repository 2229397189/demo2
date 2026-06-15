package com.agi.assistant.model.dto;

import lombok.Data;

@Data
public class SandboxExecuteRequest {

    private String language;

    private String code;

    private int timeout;
}
