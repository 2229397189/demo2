package com.agi.assistant.service;

import com.agi.assistant.model.dto.SandboxExecuteRequest;
import com.agi.assistant.model.dto.SandboxExecuteResponse;

public interface SandboxService {

    /**
     * 在沙箱中执行代码
     */
    SandboxExecuteResponse execute(SandboxExecuteRequest request);
}
