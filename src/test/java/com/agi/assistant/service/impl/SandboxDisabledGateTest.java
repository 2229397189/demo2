package com.agi.assistant.service.impl;

import com.agi.assistant.model.dto.SandboxExecuteRequest;
import com.agi.assistant.model.dto.SandboxExecuteResponse;
import com.agi.assistant.model.enums.ToolRiskLevel;
import com.agi.assistant.service.SandboxService;
import com.agi.assistant.service.security.AuditService;
import com.agi.assistant.service.security.SandboxRuntime;
import com.agi.assistant.service.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 沙箱「两道门」组合测试（全部离线，{@link SandboxRuntime} 为 stub，不真连 Docker）。
 * <p>
 * 修复前 {@code app.sandbox.enabled} 是一条「关不掉的开关」—— 全仓库只有启动日志读它，
 * 把它关掉后 {@code /api/sandbox} 与 {@code run_code} 仍照常执行。本测试锁定：
 * <ol>
 *   <li>总开关门 {@code app.sandbox.enabled=false}：执行入口直接返回「已禁用」，
 *       且 {@link SandboxRuntime#executeCode} 必须<b>零次调用</b>；</li>
 *   <li>确认门槛 {@code app.sandbox.require-confirm=true}：未确认的请求被拒绝。</li>
 * </ol>
 * 四组用例覆盖两道门的全部关键组合，确保二者都真实生效、互不覆盖。
 */
class SandboxDisabledGateTest {

    private SandboxRuntime sandboxRuntime;
    private AuditService auditService;
    private SandboxServiceImpl service;

    @BeforeEach
    void setUp() {
        sandboxRuntime = mock(SandboxRuntime.class);
        auditService = mock(AuditService.class);
        service = new SandboxServiceImpl(sandboxRuntime, auditService);
        // 默认：总开关开、确认门槛关（与 application.yml 默认一致）
        ReflectionTestUtils.setField(service, "sandboxEnabled", true);
        ReflectionTestUtils.setField(service, "requireConfirm", false);
    }

    @AfterEach
    void tearDown() {
        // UserContext 是 ThreadLocal，测试之间必须清理
        UserContext.clear();
    }

    private static SandboxExecuteRequest request(boolean confirmed) {
        SandboxExecuteRequest request = new SandboxExecuteRequest();
        request.setLanguage("python");
        request.setCode("print('hi')");
        request.setTimeout(10);
        request.setConfirmed(confirmed);
        return request;
    }

    private void stubSuccessfulExecution() {
        SandboxExecuteResponse response = new SandboxExecuteResponse();
        response.setOutput("hello");
        response.setError("");
        response.setExitCode(0);
        response.setExecutionTime(12);
        when(sandboxRuntime.executeCode(anyString(), anyString(), anyInt())).thenReturn(response);
    }

    // ── 组合 1：总开关关 → 不执行任何沙箱调用，返回「已禁用」 ──────────────────

    @Test
    @DisplayName("enabled=false：返回明确的「已禁用」结果，且 executeCode 零次调用")
    void disabledSandboxDoesNotExecuteAndReturnsDisabledResult() {
        ReflectionTestUtils.setField(service, "sandboxEnabled", false);

        SandboxExecuteResponse response = service.execute(request(false));

        assertThat(response.getError())
                .isNotNull()
                .startsWith(SandboxService.SANDBOX_DISABLED_PREFIX)
                .contains("app.sandbox.enabled=false");
        assertThat(response.getExitCode())
                .as("拒绝语义：非零退出码")
                .isNotZero();
        // 最关键断言：执行入口绝不触及底层沙箱
        verify(sandboxRuntime, never()).executeCode(anyString(), anyString(), anyInt());
        // 被总开关拒绝也要留痕（BLOCK）
        verify(auditService).log(any(), eq("SANDBOX_EXECUTE"), anyString(),
                eq(ToolRiskLevel.BLOCK), eq(true), contains("disabled"));
    }

    @Test
    @DisplayName("enabled=false + require-confirm=true + confirmed=true：总开关优先，仍不执行")
    void disabledWinsOverConfirmationGate() {
        ReflectionTestUtils.setField(service, "sandboxEnabled", false);
        ReflectionTestUtils.setField(service, "requireConfirm", true);
        stubSuccessfulExecution();

        SandboxExecuteResponse response = service.execute(request(true));

        assertThat(response.getError())
                .as("总开关关闭是更强的全局门，即使已确认也不得执行")
                .startsWith(SandboxService.SANDBOX_DISABLED_PREFIX);
        verify(sandboxRuntime, never()).executeCode(anyString(), anyString(), anyInt());
    }

    // ── 组合 2：总开关开 + 确认门槛开 + 未确认 → 被确认门槛拦截 ──────────────

    @Test
    @DisplayName("enabled=true + require-confirm=true + 未确认：被确认门槛拦截，不执行")
    void enabledAndConfirmRequiredButUnconfirmedIsRejected() {
        ReflectionTestUtils.setField(service, "requireConfirm", true);
        stubSuccessfulExecution();

        assertThatThrownBy(() -> service.execute(request(false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confirmed=true");

        verify(sandboxRuntime, never()).executeCode(anyString(), anyString(), anyInt());
        verify(auditService).log(any(), eq("SANDBOX_EXECUTE"), anyString(),
                eq(ToolRiskLevel.BLOCK), eq(true), contains("not confirmed"));
    }

    // ── 组合 3：总开关开 + 确认门槛开 + 已确认 → 正常执行 ───────────────────

    @Test
    @DisplayName("enabled=true + require-confirm=true + confirmed=true：正常执行")
    void enabledAndConfirmedExecutes() {
        ReflectionTestUtils.setField(service, "requireConfirm", true);
        stubSuccessfulExecution();

        SandboxExecuteResponse response = service.execute(request(true));

        assertThat(response.getOutput()).isEqualTo("hello");
        verify(sandboxRuntime).executeCode(eq("python"), eq("print('hi')"), eq(10));
    }

    // ── 组合 4：总开关开 + 确认门槛关 → 正常执行 ───────────────────────────

    @Test
    @DisplayName("enabled=true + require-confirm=false：不带确认也正常执行")
    void enabledWithoutConfirmationExecutes() {
        stubSuccessfulExecution();

        SandboxExecuteResponse response = service.execute(request(false));

        assertThat(response.getExitCode()).isZero();
        verify(sandboxRuntime).executeCode(eq("python"), eq("print('hi')"), eq(10));
    }
}
