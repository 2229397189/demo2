package com.agi.assistant.service;

import com.agi.assistant.model.dto.SandboxExecuteRequest;
import com.agi.assistant.model.dto.SandboxExecuteResponse;

public interface SandboxService {

    /**
     * 沙箱被运维总开关（{@code app.sandbox.enabled=false}）关闭时，
     * {@link #execute(SandboxExecuteRequest)} 返回结果的 {@code error} 字段以此前缀开头。
     * <p>
     * 约定此稳定前缀，是为了让工具侧（{@code run_code}）能把「沙箱已禁用」识别为
     * <b>拒绝语义</b>并转成结构化失败，而不是把一条拒绝信息误当成正常执行输出。
     */
    String SANDBOX_DISABLED_PREFIX = "沙箱已禁用（app.sandbox.enabled=false）";

    /**
     * 在沙箱中执行代码
     */
    SandboxExecuteResponse execute(SandboxExecuteRequest request);
}
