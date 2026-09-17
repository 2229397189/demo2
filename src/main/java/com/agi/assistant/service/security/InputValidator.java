package com.agi.assistant.service.security;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 输入验证器
 * <p>
 * 对用户输入进行多维度安全检测，并按<b>三级严重度</b>处置：
 * <ul>
 *   <li>{@link Severity#BLOCK} —— 高置信度攻击特征，直接拒绝请求</li>
 *   <li>{@link Severity#WARN}  —— 只是「看起来像」危险内容，记录审计但不拦截</li>
 *   <li>{@link Severity#CLEAN} —— 无异常</li>
 * </ul>
 *
 * <h3>为什么要分级（本次修复的核心）</h3>
 * 修复前这里是二元的：命中任意一条规则 → {@code isValid() == false} → 整条消息被拒。
 * 而规则集里既有「真注入特征」也有「敏感词」「CSS 危险属性」这类弱信号，
 * 于是一个<b>学习助手</b>会拒答下面这些完全正常的提问：
 * <pre>
 *   "SQL 里 select 和 from 的执行顺序是怎样的？"   ← 命中 SQL 模式
 *   "&lt;script&gt; 标签为什么会被用来做 XSS？"       ← 命中 XSS 模式
 *   "什么是路径穿越攻击？../ 是怎么被利用的？"       ← 命中路径遍历模式
 * </pre>
 * 也就是说，用户越是想了解这些攻击，越会被自己的助手拒之门外 —— 安全策略
 * 反过来伤害了它本该保护的功能。
 * <p>
 * 正确的做法是：<b>把拦截留给高置信度特征，把弱信号降级为审计</b>。
 * 真正的防线应该在「输出编码 + 不把用户输入当代码执行」上，而不是靠关键词墙。
 *
 * @see ToolRiskClassifier 工具侧的风险分级（对应「动作确认分级」）
 */
@Slf4j
@Component
public class InputValidator {

    // ──────────────────────────────────────────────────────────────
    //  严重度
    // ──────────────────────────────────────────────────────────────

    /** 输入风险严重度 */
    public enum Severity {
        /** 无异常 */
        CLEAN,
        /** 可疑但不拦截：记审计、打日志 */
        WARN,
        /** 高置信度攻击：拒绝执行 */
        BLOCK
    }

    // ──────────────────────────────────────────────────────────────
    //  阻断级：高置信度攻击特征
    //  原则：宁可少拦一个，也不错杀一片。只保留「正常技术讨论
    //  几乎不可能原样出现」的载荷形态。
    // ──────────────────────────────────────────────────────────────

    /** SQL 注入载荷（联合查询 / 恒真式 / 注释注入 / 堆叠语句 / 文件读写 / 延时盲注） */
    private static final Pattern SQL_INJECTION_PAYLOAD = Pattern.compile(
            "(?i)(\\bunion\\b\\s+(all\\s+)?\\bselect\\b)" +
            "|(\\b(or|and)\\b\\s+['\"]?\\d+['\"]?\\s*=\\s*['\"]?\\d+)" +
            "|(['\"]\\s*(or|and)\\s*['\"])" +
            "|(--\\s*$)|(--\\s+\\S)" +
            "|(;\\s*(drop|delete|update|insert|select|truncate|alter)\\b)" +
            "|(\\binformation_schema\\b)" +
            "|(\\binto\\s+(outfile|dumpfile)\\b)" +
            "|(\\bload_file\\s*\\()" +
            "|(\\bsleep\\s*\\(\\s*\\d)" +
            "|(\\bbenchmark\\s*\\()" +
            "|(\\bxp_cmdshell\\b)"
    );

    /** 命令注入载荷（管道接 shell / 命令串联 / 命令替换 / 反弹 shell / 直接调 shell） */
    private static final Pattern COMMAND_INJECTION_PAYLOAD = Pattern.compile(
            "(?i)([;|&]{1,2}\\s*(rm|mv|cp|chmod|chown|curl|wget|nc|ncat|bash|sh|zsh|python|perl|ruby)\\b)" +
            "|(\\|\\s*(ba)?sh\\b)" +
            "|(`[^`]{1,200}`)" +
            "|(\\$\\([^)]{1,200}\\))" +
            "|(/bin/(ba)?sh\\b)" +
            "|(\\b(cmd\\.exe|powershell\\.exe)\\b)" +
            "|(\\brm\\s+-[rf]{1,2}\\b)" +
            "|(\\bnc\\s+-[el]\\b)" +
            "|(\\bexec\\s*\\(\\s*['\"])"
    );

    /** 针对敏感文件的路径穿越（编码变体也算） */
    private static final Pattern PATH_TRAVERSAL_PAYLOAD = Pattern.compile(
            "(?i)(/etc/(passwd|shadow|sudoers|hosts))" +
            "|(\\\\windows\\\\system32)" +
            "|(%2e%2e%2f|%2e%2e/|\\.\\.%2f)" +
            "|(%252e%252e)" +
            "|(\\.\\./\\.\\./\\.\\./)" +
            "|(\\bfile:///(etc|proc|sys))" +
            "|(/proc/self/environ)"
    );

    // ──────────────────────────────────────────────────────────────
    //  警告级：弱信号，仅审计不拦截
    // ──────────────────────────────────────────────────────────────

    /** 「像 SQL」的弱信号：单独出现完全可能是正常提问 */
    private static final Pattern SQL_SHAPED = Pattern.compile(
            "(?i)\\b(select|insert|update|delete|drop|alter|truncate|create)\\b\\s+\\S{0,80}?\\b(from|where|into|table|values|set)\\b"
    );

    /** 「像 XSS」的弱信号：标签/事件属性本身是正常知识点 */
    private static final Pattern XSS_SHAPED = Pattern.compile(
            "(?i)(<script[^>]*>)|(</script>)|(javascript\\s*:)|(on(error|load|click)\\s*=)" +
            "|(<iframe[^>]*>)|(<svg[^>]+onload)|(data\\s*:\\s*text/html)"
    );

    /** 「像路径遍历」的弱信号 */
    private static final Pattern PATH_SHAPED = Pattern.compile(
            "(\\.\\./)|(\\.\\.\\\\)|(\\bfile://)|(\\b(proc|sys|dev)/)"
    );

    /** 敏感词（政策类）：命中只记录，不拒绝 —— 用户可能是在讨论如何防范 */
    private static final List<Pattern> SENSITIVE_WORD_PATTERNS = List.of(
            Pattern.compile("(?i)(色情|赌博|毒品|枪支|暴恐|代开发票|办证|洗钱)")
    );

    // ──────────────────────────────────────────────────────────────
    //  公共 API
    // ──────────────────────────────────────────────────────────────

    /**
     * 对输入内容进行全面验证。
     *
     * @param input 待验证的输入内容
     * @return 验证结果（含严重度、阻断项、警告项）
     */
    public ValidationResult validate(String input) {
        if (input == null || input.isBlank()) {
            return new ValidationResult(true, Severity.CLEAN, List.of(), List.of());
        }

        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // 阻断级检测
        if (SQL_INJECTION_PAYLOAD.matcher(input).find()) {
            blockers.add("检测到 SQL 注入载荷");
        }
        if (COMMAND_INJECTION_PAYLOAD.matcher(input).find()) {
            blockers.add("检测到命令注入载荷");
        }
        if (PATH_TRAVERSAL_PAYLOAD.matcher(input).find()) {
            blockers.add("检测到敏感路径遍历");
        }

        // 警告级检测
        if (SQL_SHAPED.matcher(input).find()) {
            warnings.add("输入包含类 SQL 语句结构");
        }
        if (XSS_SHAPED.matcher(input).find()) {
            warnings.add("输入包含类 XSS 标签或事件属性");
        }
        if (PATH_SHAPED.matcher(input).find()) {
            warnings.add("输入包含路径跳转片段");
        }

        List<String> sensitiveWords = detectSensitiveWords(input);
        if (!sensitiveWords.isEmpty()) {
            warnings.add("命中敏感词: " + String.join(", ", sensitiveWords));
        }

        Severity severity = !blockers.isEmpty() ? Severity.BLOCK
                : (!warnings.isEmpty() ? Severity.WARN : Severity.CLEAN);

        if (severity == Severity.BLOCK) {
            log.warn("Input BLOCKED: violations={}", blockers);
        } else if (severity == Severity.WARN) {
            // 降级为 debug：这些是学习场景里的正常提问，不该刷 warn 日志
            log.debug("Input flagged as suspicious but allowed: {}", warnings);
        }

        return new ValidationResult(severity != Severity.BLOCK, severity, blockers, warnings);
    }

    /**
     * 快速验证，仅返回是否放行。
     * <p>
     * 注意语义：只有 {@link Severity#BLOCK} 才算不合法；
     * {@link Severity#WARN} 是「放行但留痕」。
     *
     * @param input 待验证的输入内容
     * @return true 表示可以放行
     */
    public boolean isValid(String input) {
        return validate(input).isValid();
    }

    /**
     * 是否需要记录安全审计。
     *
     * @param input 待检查的输入
     * @return true 表示存在阻断或警告级信号
     */
    public boolean needsAudit(String input) {
        return validate(input).getSeverity() != Severity.CLEAN;
    }

    // ──────────────────────────────────────────────────────────────
    //  检测方法（保留细粒度入口，便于测试与复用）
    // ──────────────────────────────────────────────────────────────

    /**
     * 检测高置信度 SQL 注入载荷。
     */
    public boolean detectSqlInjection(String input) {
        return input != null && SQL_INJECTION_PAYLOAD.matcher(input).find();
    }

    /**
     * 检测高置信度 XSS 载荷。
     * <p>
     * 说明：标签/事件属性类信号已降级为警告（见 {@link #detectXssShaped(String)}），
     * 因为「&lt;script&gt; 是什么」是学习场景的正常提问，而不是攻击。
     * 保留本方法以兼容既有调用方，返回的是「明确的数据 URI / javascript: 伪协议」这类载荷。
     */
    public boolean detectXss(String input) {
        if (input == null) {
            return false;
        }
        return Pattern.compile("(?i)(javascript\\s*:\\s*(alert|eval|document))|(data\\s*:\\s*text/html)")
                .matcher(input).find();
    }

    /**
     * 检测「像 XSS」的弱信号（仅告警）。
     */
    public boolean detectXssShaped(String input) {
        return input != null && XSS_SHAPED.matcher(input).find();
    }

    /**
     * 检测高置信度命令注入载荷。
     */
    public boolean detectCommandInjection(String input) {
        return input != null && COMMAND_INJECTION_PAYLOAD.matcher(input).find();
    }

    /**
     * 检测针对敏感文件的路径遍历。
     */
    public boolean detectPathTraversal(String input) {
        return input != null && PATH_TRAVERSAL_PAYLOAD.matcher(input).find();
    }

    /**
     * 检测敏感词，返回匹配到的敏感词列表。
     */
    public List<String> detectSensitiveWords(String input) {
        if (input == null) {
            return List.of();
        }

        List<String> found = new ArrayList<>();
        for (Pattern pattern : SENSITIVE_WORD_PATTERNS) {
            var matcher = pattern.matcher(input);
            while (matcher.find()) {
                found.add(matcher.group());
            }
        }
        return found;
    }

    // ──────────────────────────────────────────────────────────────
    //  结果类
    // ──────────────────────────────────────────────────────────────

    /**
     * 验证结果
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationResult {

        /** 是否放行 */
        private boolean valid;

        /** 严重度 */
        private Severity severity;

        /** 阻断项（导致拒绝） */
        private List<String> blockers;

        /** 警告项（记录但不拒绝） */
        private List<String> warnings;

        /**
         * 是否放行（兼容 getter 风格）。
         */
        public boolean isValid() {
            return valid;
        }

        /**
         * 全部违规项（阻断 + 警告），兼容旧调用方。
         */
        public List<String> getViolations() {
            List<String> all = new ArrayList<>();
            if (blockers != null) {
                all.addAll(blockers);
            }
            if (warnings != null) {
                all.addAll(warnings);
            }
            return all;
        }
    }
}
