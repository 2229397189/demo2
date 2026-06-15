package com.agi.assistant.model.dto;

import lombok.Data;

@Data
public class DocumentUploadRequest {

    private String title;

    private String tags;

    private String source;
}
