package com.agi.assistant.service.impl;

import com.agi.assistant.model.dto.SandboxExecuteRequest;
import com.agi.assistant.model.dto.SandboxExecuteResponse;
import com.agi.assistant.model.enums.ToolRiskLevel;
import com.agi.assistant.service.security.AuditService;
import com.agi.assistant.service.security.SandboxRuntime;
import com.agi.assistant.service.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
 * {@link SandboxServiceImpl} 测试：确认门槛（此前 sandbox.require-confirm 是
 * 无人读取的装饰性配置）+ 沙箱执行审计（P0-10 补口）。
 */
class SandboxServiceImplTest {

    private SandboxRuntime sandboxRuntime;
    private AuditService auditService;
    private SandboxServiceImpl service;

    @BeforeEach
    void setUp() {
        sandboxRuntime = mock(SandboxRuntime.class);
        auditService = mock(AuditService.class);
        service = new SandboxServiceImpl(sandboxRuntime, auditService);
        ReflectionTestUtils.setField(service, "requireConfirm", false);
    }

    @AfterEach
    void tearDown() {
        // UserContext 是 ThreadLocal，测试之间必须清理，否则会串号
        UserContext.clear();
    }

    private static SandboxExecuteRequest request(String code) {
        SandboxExecuteRequest request = new SandboxExecuteRequest();
        request.setLanguage("python");
        request.setCode(code);
        request.setTimeout(10);
        return request;
    }

    private void stubSuccessfulExecution() {
        SandboxExecuteResponse response = new SandboxExecuteResponse();
        response.setOutput("hello");
        response.setExitCode(0);
        response.setExecutionTime(12);
        when(sandboxRuntime.executeCode(anyString(), anyString(), anyInt()))
                .thenReturn(response);
    }

    @Test
    @DisplayName("require-confirm=false：未确认请求照常执行（保持既有行为）")
    void confirmationNotRequiredByDefault() {
        stubSuccessfulExecution();

        SandboxExecuteResponse response = service.execute(request("print('hi')"));

        assertThat(response.getOutput()).isEqualTo("hello");
        verify(sandboxRuntime).executeCode(eq("python"), anyString(), eq(10));
    }

    @Test
    @DisplayName("require-confirm=true 且未确认：拒绝执行并记 BLOCK 审计")
    void unconfirmedRequestIsRejectedWhenConfirmationRequired() {
        ReflectionTestUtils.setField(service, "requireConfirm", true);

        assertThatThrownBy(() -> service.execute(request("print('hi')")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confirmed=true");

        verify(sandboxRuntime, never()).executeCode(anyString(), anyString(), anyInt());
        verify(auditService).log(any(), eq("SANDBOX_EXECUTE"), anyString(),
                eq(ToolRiskLevel.BLOCK), eq(true), contains("not confirmed"));
    }

    @Test
    @DisplayName("require-confirm=true 且已确认：正常执行")
    void confirmedRequestExecutes() {
        ReflectionTestUtils.setField(service, "requireConfirm", true);
        stubSuccessfulExecution();
        SandboxExecuteRequest req = request("print('hi')");
        req.setConfirmed(true);

        SandboxExecuteResponse response = service.execute(req);

        assertThat(response.getExitCode()).isZero();
        verify(sandboxRuntime).executeCode(eq("python"), anyString(), eq(10));
    }

    @Test
    @DisplayName("每次执行都写审计（P0-10：沙箱曾是审计盲区）")
    void executionIsAudited() {
        stubSuccessfulExecution();

        service.execute(request("print('hi')"));

        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(any(), action.capture(), anyString(),
                eq(ToolRiskLevel.WARN), eq(false), anyString());
        assertThat(action.getValue()).isEqualTo("SANDBOX_EXECUTE");
    }

    @Test
    @DisplayName("认证用户身份会进入审计记录")
    void userIdentityFlowsIntoAudit() {
        stubSuccessfulExecution();
        UserContext.setUserId(42L);

        service.execute(request("print('hi')"));

        verify(auditService).log(eq(42L), eq("SANDBOX_EXECUTE"), anyString(),
                eq(ToolRiskLevel.WARN), eq(false), anyString());
    }
}
