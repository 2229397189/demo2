package com.agi.assistant.model.dto;

import lombok.Data;

@Data
public class ChatRequest {

    private String message;

    private Long sessionId;

    private boolean useMemory = false;

    private String retrievalStrategy = "NONE";

    private boolean stream = true;
}
