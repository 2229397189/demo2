package com.agi.assistant.model.enums;

import lombok.Getter;

@Getter
public enum DocumentStatus {

    PENDING(0, "待处理"),
    PROCESSING(1, "处理中"),
    COMPLETED(2, "已完成"),
    FAILED(3, "处理失败"),
    /**
     * 部分完成：分块已落库，但至少一路索引（向量 / BM25 / 图谱）写入失败，
     * 而其余路径成功。语义上区别于 COMPLETED（全链路成功）与 FAILED（全链路失败），
     * 用于避免「索引失败却上报成功」的假成功。
     */
    PARTIAL(4, "部分完成");

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
