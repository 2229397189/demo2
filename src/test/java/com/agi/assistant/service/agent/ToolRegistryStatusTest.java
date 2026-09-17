package com.agi.assistant.service.agent;

import com.agi.assistant.model.dto.ToolResult;
import com.agi.assistant.model.enums.ToolRiskLevel;
import com.agi.assistant.model.enums.ToolStatus;
import com.agi.assistant.service.security.AuditService;
import com.agi.assistant.service.security.ToolRiskClassifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 工具「从未执行」状态单测（缺陷：注册时硬编码 SUCCESS → {@code /api/agent/tools} 假报成功）。
 *
 * @author Alex
 */
class ToolRegistryStatusTest {

    @Test
    @DisplayName("刚注册、从未执行过的工具，其 status 不是 SUCCESS 而是 NOT_EXECUTED")
    void newlyRegisteredToolIsNotReportedAsSuccess() {
        ToolRegistry registry = new ToolRegistry(
                new ToolRiskClassifier(), mock(AuditService.class), mock(ToolExecutorService.class));

        registry.registerTool("echo", "回显工具", ToolRiskLevel.SAFE,
                (ToolHandler) params -> ToolResult.success("echo", "ok", null));

        assertThat(registry.listTools()).hasSize(1);
        ToolRegistry.ToolDefinition tool = registry.listTools().get(0);
        assertThat(tool.getStatus()).isEqualTo(ToolStatus.NOT_EXECUTED);
        assertThat(tool.getStatus())
                .as("从未执行过的工具绝不能上报 SUCCESS")
                .isNotEqualTo(ToolStatus.SUCCESS);
    }
}
