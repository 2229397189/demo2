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
 *   <li>{@code app.sandbox.enabled=false} 时，执行入口直接返回「已禁用」结果，
 *       不创建、不执行任何容器（此前该总开关只被启动日志读取，关掉也不生效）；</li>
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
     * <p>
     * 属性键对齐说明：application.yml 里该开关实际位于 {@code app.sandbox.require-confirm}，
     * 而此前 @Value 读的是 {@code sandbox.require-confirm} —— 键不一致导致即便在 yml 里
     * 打开开关，本服务也永远读到默认值 false，确认门槛形同虚设。现以 yml 的
     * {@code app.sandbox.require-confirm} 为准，并回退兼容旧的 {@code sandbox.require-confirm}。
     */
    @Value("${app.sandbox.require-confirm:${sandbox.require-confirm:false}}")
    private boolean requireConfirm;

    /**
     * 沙箱执行总开关，挂载于 {@code app.sandbox.enabled}。
     * <p>
     * 关闭（false）时 {@link #execute(SandboxExecuteRequest)} 在<b>执行入口</b>直接拒绝，
     * <b>不创建、不执行任何容器</b>，返回带明确中文原因的「已禁用」结果。
     * <p>
     * 属性键对齐说明：与 {@code app.sandbox.require-confirm} 一致，读 {@code app.sandbox.enabled}
     * （不是沙箱模块局部的 {@code sandbox.enabled}）。
     * <p>
     * 字段初始值设为 {@code true}：生产环境由 Spring {@code @Value} 注入真实配置；
     * 单元测试直接 {@code new} 该服务时保持「默认启用」，避免误触发禁用分支。
     */
    @Value("${app.sandbox.enabled:true}")
    private boolean sandboxEnabled = true;

    @Override
    public SandboxExecuteResponse execute(SandboxExecuteRequest request) {
        String language = request.getLanguage() == null ? "unknown" : request.getLanguage();
        Long userId = UserContext.getUserId();

        // 第一道门：沙箱总开关（app.sandbox.enabled）。
        // 关闭时直接拒绝，绝不触及 SandboxRuntime —— 修复前该键只被启动日志读取，
        // 把它关掉后 /api/sandbox 与 run_code 照常执行，属于「配置撒谎」。
        if (!sandboxEnabled) {
            log.warn("Sandbox execute refused: app.sandbox.enabled=false");
            auditService.log(userId, "SANDBOX_EXECUTE", "sandbox:" + language,
                    ToolRiskLevel.BLOCK, true,
                    "rejected: sandbox disabled (app.sandbox.enabled=false)");
            return new SandboxExecuteResponse(
                    "",
                    SandboxService.SANDBOX_DISABLED_PREFIX
                            + "：该能力已被运维开关关闭，不会执行任何代码。",
                    0L);
        }

        log.info("Sandbox execute: language={}, timeout={}, codeLength={}",
                request.getLanguage(), request.getTimeout(),
                request.getCode() != null ? request.getCode().length() : 0);

        // 第二道门：确认门槛（app.sandbox.require-confirm），独立于总开关 ——
        // 总开关打开时，require-confirm=true 且未确认的请求仍被拒绝。
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
