package com.agi.assistant.service.security;

import com.agi.assistant.model.enums.ToolRiskLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 工具风险分类器
 * <p>
 * 根据工具名称、参数内容和上下文信息对工具调用进行风险分级：
 * <ul>
 *   <li>SAFE：安全操作，无需额外审批</li>
 *   <li>WARN：中等风险，需记录审计日志</li>
 *   <li>BLOCK：高危操作，直接阻断</li>
 * </ul>
 */
@Slf4j
@Component
public class ToolRiskClassifier {

    /** 已知的安全工具名称（白名单） */
    private static final Set<String> SAFE_TOOLS = Set.of(
            "search", "query", "read", "list", "get", "fetch", "lookup",
            "calculate", "format", "translate", "summarize", "analyze"
    );

    /** 已知的警告级工具名称 */
    private static final Set<String> WARN_TOOLS = Set.of(
            "write", "update", "modify", "insert", "upload", "send",
            "notify", "create", "draft", "post"
    );

    /** 已知的阻断级工具名称（黑名单） */
    private static final Set<String> BLOCK_TOOLS = Set.of(
            "delete", "drop", "truncate", "destroy", "rm", "kill",
            "shutdown", "reboot", "format", "exec", "eval", "sudo",
            "chmod", "chown", "useradd", "userdel"
    );

    /** 危险参数模式：SQL 注入、命令注入等 */
    private static final Pattern DANGEROUS_PARAM_PATTERNS = Pattern.compile(
            "(?i)(drop\\s+table|delete\\s+from|exec(ute)?\\s*\\(|system\\s*\\(|" +
            "cmd|powershell|/bin/bash|/bin/sh|rm\\s+-rf|wget\\s|curl\\s.*\\|\\s*sh)"
    );

    /** 敏感数据模式：密钥、密码等 */
    private static final Pattern SENSITIVE_DATA_PATTERN = Pattern.compile(
            "(?i)(password|secret|token|api[_-]?key|private[_-]?key|credentials|" +
            "aws[_-]?access|mysql|jdbc:|mongodb://|redis://)"
    );

    /**
     * 对工具调用进行风险分类。
     * <p>
     * <b>使用建议</b>：对已注册工具（{@code ToolRegistry} 内的工具）不要用本方法 ——
     * 已注册工具应当由自己声明的 {@code riskLevel} 说了算，本方法的
     * 「未知名称一律 WARN」会把所有自研工具都误判成 WARN，导致每次调用都刷警告日志。
     * 已注册工具请用 {@link #isBlockedName(String)} + {@link #classifyParamsOnly(String)}。
     *
     * @param toolName 工具名称
     * @param params   工具参数（JSON 字符串或纯文本）
     * @return 风险等级
     */
    public ToolRiskLevel classify(String toolName, String params) {
        if (toolName == null || toolName.isBlank()) {
            log.warn("Empty tool name, classifying as WARN");
            return ToolRiskLevel.WARN;
        }

        String normalizedName = toolName.trim().toLowerCase();

        // 1. 检查工具名称黑名单
        if (BLOCK_TOOLS.contains(normalizedName)) {
            log.warn("Tool [{}] is in BLOCK list", toolName);
            return ToolRiskLevel.BLOCK;
        }

        // 2. 检查工具名称白名单
        if (SAFE_TOOLS.contains(normalizedName)) {
            // 即使工具本身安全，参数中也可能有危险内容
            if (params != null && hasDangerousParams(params)) {
                log.warn("Safe tool [{}] has dangerous params, upgrading to WARN", toolName);
                return ToolRiskLevel.WARN;
            }
            return ToolRiskLevel.SAFE;
        }

        // 3. 检查工具名称警告级
        if (WARN_TOOLS.contains(normalizedName)) {
            if (params != null && hasDangerousParams(params)) {
                log.warn("Warn tool [{}] has dangerous params, upgrading to BLOCK", toolName);
                return ToolRiskLevel.BLOCK;
            }
            return ToolRiskLevel.WARN;
        }

        // 4. 未知工具默认为 WARN
        log.info("Unknown tool [{}], classifying as WARN", toolName);
        if (params != null && hasDangerousParams(params)) {
            return ToolRiskLevel.BLOCK;
        }
        return ToolRiskLevel.WARN;
    }

    /**
     * 名称是否命中硬红线黑名单（delete/drop/exec/sudo 等）。
     * <p>
     * 这是不依赖注册状态的硬约束：即使某个高危工具被误注册进注册表，也不放行。
     *
     * @param toolName 工具名称
     * @return true 表示必须阻断
     */
    public boolean isBlockedName(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        return BLOCK_TOOLS.contains(toolName.trim().toLowerCase());
    }

    /**
     * 只根据「参数内容」判定风险，不看工具名。
     * <p>
     * 用于已注册工具：基础风险由工具自己声明，本方法只负责在参数里
     * 发现注入/危险命令时把风险等级往上抬。
     *
     * @param params 工具参数
     * @return 参数侧的风险等级（无危险参数时返回 SAFE）
     */
    public ToolRiskLevel classifyParamsOnly(String params) {
        if (params == null || params.isBlank()) {
            return ToolRiskLevel.SAFE;
        }
        if (hasDangerousParams(params)) {
            log.warn("Tool params contain dangerous pattern, raising risk to WARN");
            return ToolRiskLevel.WARN;
        }
        return ToolRiskLevel.SAFE;
    }

    /**
     * 简化分类接口，仅通过工具名称判断。
     *
     * @param toolName 工具名称
     * @return 风险等级
     */
    public ToolRiskLevel classify(String toolName) {
        return classify(toolName, null);
    }

    /**
     * 检查参数中是否包含危险内容。
     */
    private boolean hasDangerousParams(String params) {
        return DANGEROUS_PARAM_PATTERNS.matcher(params).find();
    }

    /**
     * 检查参数中是否包含敏感数据。
     *
     * @param params 工具参数
     * @return true 如果包含敏感数据
     */
    public boolean containsSensitiveData(String params) {
        if (params == null) {
            return false;
        }
        return SENSITIVE_DATA_PATTERN.matcher(params).find();
    }
}
