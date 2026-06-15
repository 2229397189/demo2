package com.agi.assistant.model.enums;

import lombok.Getter;

@Getter
public enum DocumentStatus {

    PENDING(0, "待处理"),
    PROCESSING(1, "处理中"),
    COMPLETED(2, "已完成"),
    FAILED(3, "处理失败");

    private final int code;
    private final String desc;

    DocumentStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static DocumentStatus fromCode(int code) {
        for (DocumentStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown DocumentStatus code: " + code);
    }
}
