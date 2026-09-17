package com.agi.assistant.controller;

import com.agi.assistant.mapper.AuditLogMapper;
import com.agi.assistant.model.vo.Result;
import com.agi.assistant.service.agent.DAGScheduler;
import com.agi.assistant.service.agent.ToolRegistry;
import com.agi.assistant.service.harness.HarnessRuntime;
import com.agi.assistant.service.security.AuthenticationException;
import com.agi.assistant.service.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentController#executeTool} 的身份归属离线单测。
 * <p>
 * 旧实现用 {@code @RequestHeader("X-User-Id")} 且 {@code default=1} 取身份 —— 任何人带一个
 * {@code X-User-Id} 头就能冒充任意用户执行工具（越权 + 审计污染）。修复后：
 * 未认证 → 401；已认证 → 用服务端上下文里的身份，请求头不再具权威性。
 *
 * @author Alex
 */
class AgentControllerOwnershipTest {

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @DisplayName("executeTool：未认证被拒；已认证则用上下文身份（不再信任请求头）")
    void executeToolUsesAuthenticatedIdentity() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        AgentController controller = new AgentController(
                toolRegistry, mock(DAGScheduler.class), mock(HarnessRuntime.class),
                mock(AuditLogMapper.class));

        when(toolRegistry.hasTool("echo")).thenReturn(true);
        when(toolRegistry.executeTool(eq("echo"), any(), eq(7L)))
                .thenReturn(Map.of("status", "SUCCESS", "result", "ok"));

        // 未认证（UserContext 无值）→ 拒绝
        assertThatThrownBy(() -> controller.executeTool("echo", Map.of()))
                .isInstanceOf(AuthenticationException.class);

        // 已认证用户 7 → 正常返回，且工具以「用户 7」的身份执行
        UserContext.setUserId(7L);
        Result<Map<String, Object>> result = controller.executeTool("echo", Map.of());
        assertThat(result.getData()).containsEntry("status", "SUCCESS");
        verify(toolRegistry).executeTool(eq("echo"), any(), eq(7L));
    }
}
