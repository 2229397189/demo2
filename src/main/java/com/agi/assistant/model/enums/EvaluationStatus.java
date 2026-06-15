package com.agi.assistant.model.enums;

import lombok.Getter;

@Getter
public enum EvaluationStatus {

    PENDING(0, "待评估"),
    RUNNING(1, "评估中"),
    COMPLETED(2, "已完成"),
    FAILED(3, "评估失败");

    private final int code;
    private final String desc;

    EvaluationStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static EvaluationStatus fromCode(int code) {
        for (EvaluationStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown EvaluationStatus code: " + code);
    }
}
