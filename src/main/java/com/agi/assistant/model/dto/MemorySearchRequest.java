package com.agi.assistant.model.dto;

import lombok.Data;

@Data
public class MemorySearchRequest {

    private Long userId;

    private String query;

    private int topK;
}
