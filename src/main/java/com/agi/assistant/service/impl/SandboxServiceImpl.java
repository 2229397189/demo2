package com.agi.assistant.service.impl;

import com.agi.assistant.model.dto.SandboxExecuteRequest;
import com.agi.assistant.model.dto.SandboxExecuteResponse;
import com.agi.assistant.service.SandboxService;
import com.agi.assistant.service.security.SandboxRuntime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * SandboxService implementation.
 * <p>
 * Delegates code execution to the Docker-based SandboxRuntime,
 * providing a secure isolated environment for running user code.
 */
@Slf4j
@Lazy
@Service
@RequiredArgsConstructor
public class SandboxServiceImpl implements SandboxService {

    private final SandboxRuntime sandboxRuntime;

    @Override
    public SandboxExecuteResponse execute(SandboxExecuteRequest request) {
        log.info("Sandbox execute: language={}, timeout={}, codeLength={}",
                request.getLanguage(), request.getTimeout(),
                request.getCode() != null ? request.getCode().length() : 0);

        SandboxExecuteResponse response = sandboxRuntime.executeCode(
                request.getLanguage(),
                request.getCode(),
                request.getTimeout());

        log.info("Sandbox execution completed: executionTime={}ms, hasError={}",
                response.getExecutionTime(),
                response.getError() != null && !response.getError().isEmpty());

        return response;
    }
}
