package com.agi.assistant.service.impl;

import com.agi.assistant.model.dto.SandboxExecuteRequest;
import com.agi.assistant.model.dto.SandboxExecuteResponse;
import com.agi.assistant.model.enums.ToolRiskLevel;
import com.agi.assistant.service.SandboxService;
import com.agi.assistant.service.security.AuditService;
import com.agi.assistant.service.security.SandboxRuntime;
import com.agi.assistant.service.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * SandboxService implementation.
 * <p>
 * Delegates code execution to the Docker-based SandboxRuntime,
 * providing a secure isolated environment for running user code.
 * <p>
 * 修复说明：沙箱执行是「运行任意代码」的高风险动作，此前却既没有审计
 * （P0-10 补口之一），{@code sandbox.require-confirm} 也只是 yml 里的装饰性
 * 配置 —— 全仓库没有任何代码读它。现在：
 * <ul>
 *   <li>每次执行（成功/失败/被拒）都写 {@code audit_log}；</li>
 *   <li>{@code require-confirm=true} 时，未带 {@code confirmed=true} 的请求直接拒绝。</li>
 * </ul>
 */
@Slf4j
@Lazy
@Service
@RequiredArgsConstructor
public class SandboxServiceImpl implements SandboxService {

    private final SandboxRuntime sandboxRuntime;
    private final AuditService auditService;

    /**
     * 是否要求显式确认后才执行代码。
     * 默认 false：保持既有行为；生产建议开启。
     */
    @Value("${sandbox.require-confirm:false}")
    private boolean requireConfirm;

    @Override
    public SandboxExecuteResponse execute(SandboxExecuteRequest request) {
        log.info("Sandbox execute: language={}, timeout={}, codeLength={}",
                request.getLanguage(), request.getTimeout(),
                request.getCode() != null ? request.getCode().length() : 0);

        String language = request.getLanguage() == null ? "unknown" : request.getLanguage();
        Long userId = UserContext.getUserId();

        // 确认门槛：装饰性配置落地（修复前全仓库无人读取 sandbox.require-confirm）
        if (requireConfirm && !Boolean.TRUE.equals(request.getConfirmed())) {
            auditService.log(userId, "SANDBOX_EXECUTE", "sandbox:" + language,
                    ToolRiskLevel.BLOCK, true,
                    "rejected: execution not confirmed (sandbox.require-confirm=true)");
            throw new IllegalArgumentException(
                    "沙箱执行被拒绝：当前配置要求显式确认（请在请求中携带 confirmed=true）");
        }

        SandboxExecuteResponse response = sandboxRuntime.executeCode(
                request.getLanguage(),
                request.getCode(),
                request.getTimeout());

        boolean failed = response.getError() != null && !response.getError().isEmpty();
        log.info("Sandbox execution completed: executionTime={}ms, hasError={}",
                response.getExecutionTime(), failed);

        // 高风险动作必须留痕（P0-10：沙箱执行此前是三处审计盲区之一）
        auditService.log(userId, "SANDBOX_EXECUTE", "sandbox:" + language,
                ToolRiskLevel.WARN, false,
                "executed: exitCode=" + response.getExitCode()
                        + ", timeMs=" + response.getExecutionTime()
                        + ", failed=" + failed);

        return response;
    }
}
