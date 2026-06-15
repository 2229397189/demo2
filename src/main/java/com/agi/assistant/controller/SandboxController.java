package com.agi.assistant.controller;

import com.agi.assistant.model.dto.SandboxExecuteRequest;
import com.agi.assistant.model.dto.SandboxExecuteResponse;
import com.agi.assistant.model.vo.Result;
import com.agi.assistant.service.SandboxService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/sandbox")
@RequiredArgsConstructor
@Tag(name = "Sandbox", description = "代码沙箱执行接口")
public class SandboxController {

    private final SandboxService sandboxService;

    @PostMapping("/execute")
    @Operation(summary = "执行代码", description = "在沙箱环境中安全执行代码")
    public Result<SandboxExecuteResponse> execute(@Valid @RequestBody SandboxExecuteRequest request) {
        log.info("Execute code in sandbox, language {}, timeout {}ms", request.getLanguage(), request.getTimeout());
        SandboxExecuteResponse response = sandboxService.execute(request);
        if (response.getError() != null && !response.getError().isEmpty()) {
            log.warn("Sandbox execution error: {}", response.getError());
        }
        return Result.ok(response);
    }
}
