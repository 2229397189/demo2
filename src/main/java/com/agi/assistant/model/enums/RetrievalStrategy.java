package com.agi.assistant.model.enums;

public enum RetrievalStrategy {
    NONE,
    DENSE,
    SPARSE,
    GRAPH,
    HYBRID,

    /**
     * 全量检索：稠密 + 稀疏 + 图谱
     */
    FULL
}
