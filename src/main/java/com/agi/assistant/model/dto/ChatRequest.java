package com.agi.assistant.model.dto;

import lombok.Data;

@Data
public class ChatRequest {

    private String message;

    private Long sessionId;

    private boolean useMemory = true;

    private String retrievalStrategy = "HYBRID";

    private boolean stream = true;
}
