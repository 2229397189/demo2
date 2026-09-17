package com.agi.assistant.model.dto;

import lombok.Data;

@Data
public class SandboxExecuteRequest {

    private String language;

    private String code;

    private int timeout;

    /**
     * 显式确认执行。
     * <p>
     * {@code sandbox.require-confirm=true} 时，未经确认的请求会被拒绝。
     * 工具调用路径（run_code）会置为 true：它已经过 ToolRiskClassifier 风险分级
     * 与审计，本身即是「经过确认的动作」；直连 API 的调用方则必须显式声明。
     */
    private Boolean confirmed;
}
