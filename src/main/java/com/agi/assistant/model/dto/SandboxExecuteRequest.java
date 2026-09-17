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
     * {@code app.sandbox.require-confirm=true} 时，未经确认的请求会被拒绝。
     * 确认状态由调用方自行声明：直连 API 的调用方必须显式传 {@code confirmed=true}；
     * 工具调用路径（{@code run_code}）同样从入参读取 {@code confirmed}，
     * 不再由工具侧硬编码为 true —— 否则 LLM 生成的调用会绕过确认门槛。
     */
    private Boolean confirmed;
}
