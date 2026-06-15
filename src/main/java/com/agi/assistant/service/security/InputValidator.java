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
 * 对用户输入进行多维度安全检测：
 * <ul>
 *   <li>SQL 注入检测</li>
 *   <li>XSS 攻击检测</li>
 *   <li>命令注入检测</li>
 *   <li>路径遍历检测</li>
 *   <li>敏感词过滤</li>
 * </ul>
 */
@Slf4j
@Component
public class InputValidator {

    // ──────────────────────────────────────────────────────────────
    //  检测正则
    // ──────────────────────────────────────────────────────────────

    /** SQL 注入模式 */
    private static final Pattern SQL_INJECTION_PATTERN = Pattern.compile(
            "(?i)(\\b(select|insert|update|delete|drop|alter|create|truncate|exec|execute|union|into)\\b" +
            "\\s.*(\\bfrom\\b|\\bwhere\\b|\\btable\\b|\\binto\\b|\\bvalues\\b|\\bset\\b))" +
            "|(--\\s)|(;\\s*(drop|delete|update|insert|select))" +
            "|(\\b(or|and)\\b\\s+\\d+\\s*=\\s*\\d+)" +
            "|('\\s*(or|and)\\s+')" +
            "|(\\b(information_schema|sysobjects|syscolumns)\\b)" +
            "|(\\b(load_file|into\\s+outfile|into\\s+dumpfile)\\b)"
    );

    /** XSS 攻击模式 */
    private static final Pattern XSS_PATTERN = Pattern.compile(
            "(?i)(<script[^>]*>)|(</script>)" +
            "|(javascript\\s*:)" +
            "|(on(load|error|click|mouse|key|focus|blur|submit|change)\\s*=)" +
            "|(<iframe[^>]*>)|(<object[^>]*>)|(<embed[^>]*>)|(<applet[^>]*>)" +
            "|(expression\\s*\\()" +
            "|(vbscript\\s*:)" +
            "|(<\\s*img[^>]+onerror)" +
            "|(<\\s*svg[^>]+onload)" +
            "|(data\\s*:\\s*text/html)"
    );

    /** 命令注入模式 */
    private static final Pattern COMMAND_INJECTION_PATTERN = Pattern.compile(
            "(?i)(;\\s*(ls|cat|rm|mv|cp|chmod|chown|wget|curl|nc|ncat|bash|sh|python|perl|ruby|php))" +
            "|(\\|\\s*(ls|cat|rm|mv|cp|chmod|chown|wget|curl|nc|ncat|bash|sh))" +
            "|(`[^`]+`)" +
            "|(\\$\\([^)]+\\))" +
            "|(&&\\s*(ls|cat|rm|wget|curl|bash|sh))" +
            "|(\\|\\|\\s*(ls|cat|rm|wget|curl|bash|sh))" +
            "|(\\b(exec|system|passthru|popen|proc_open)\\s*\\()" +
            "|(/bin/(bash|sh|zsh|csh))" +
            "|(cmd\\.exe|powershell)"
    );

    /** 路径遍历模式 */
    private static final Pattern PATH_TRAVERSAL_PATTERN = Pattern.compile(
            "(\\.\\./)|(\\.\\.\\\\)" +
            "|(/etc/(passwd|shadow|hosts|group))" +
            "|(\\\\windows\\\\)" +
            "|(%2e%2e%2f)|(%2e%2e/)|(%2e%2e\\\\)|(%252e%252e)" +
            "|(\\b(file|ftp|gopher|dict)://)" +
            "|(\\b(proc|sys|dev)/)"
    );

    /** 敏感词列表 */
    private static final List<Pattern> SENSITIVE_WORD_PATTERNS = List.of(
            Pattern.compile("(?i)(fuck|shit|damn|bitch|asshole)"),
            Pattern.compile("(?i)(色情|赌博|毒品|枪支|暴恐)"),
            Pattern.compile("(?i)(代开发票|办证|洗钱)")
    );

    // ──────────────────────────────────────────────────────────────
    //  公共 API
    // ──────────────────────────────────────────────────────────────

    /**
     * 对输入内容进行全面验证。
     *
     * @param input 待验证的输入内容
     * @return 验证结果
     */
    public ValidationResult validate(String input) {
        List<String> violations = new ArrayList<>();

        if (input == null || input.isBlank()) {
            return new ValidationResult(true, violations);
        }

        if (detectSqlInjection(input)) {
            violations.add("检测到潜在的 SQL 注入攻击");
        }

        if (detectXss(input)) {
            violations.add("检测到潜在的 XSS 攻击");
        }

        if (detectCommandInjection(input)) {
            violations.add("检测到潜在的命令注入攻击");
        }

        if (detectPathTraversal(input)) {
            violations.add("检测到潜在的路径遍历攻击");
        }

        List<String> sensitiveWords = detectSensitiveWords(input);
        if (!sensitiveWords.isEmpty()) {
            violations.add("检测到敏感词: " + String.join(", ", sensitiveWords));
        }

        boolean isValid = violations.isEmpty();
        if (!isValid) {
            log.warn("Input validation failed with {} violation(s): {}", violations.size(),
                    violations.size() > 3 ? violations.subList(0, 3) + "..." : violations);
        }

        return new ValidationResult(isValid, violations);
    }

    /**
     * 快速验证，仅返回是否合法。
     *
     * @param input 待验证的输入内容
     * @return true 如果输入合法
     */
    public boolean isValid(String input) {
        return validate(input).isValid();
    }

    // ──────────────────────────────────────────────────────────────
    //  检测方法
    // ──────────────────────────────────────────────────────────────

    /**
     * 检测 SQL 注入。
     */
    public boolean detectSqlInjection(String input) {
        if (input == null) return false;
        boolean detected = SQL_INJECTION_PATTERN.matcher(input).find();
        if (detected) {
            log.debug("SQL injection pattern detected in input");
        }
        return detected;
    }

    /**
     * 检测 XSS 攻击。
     */
    public boolean detectXss(String input) {
        if (input == null) return false;
        boolean detected = XSS_PATTERN.matcher(input).find();
        if (detected) {
            log.debug("XSS pattern detected in input");
        }
        return detected;
    }

    /**
     * 检测命令注入。
     */
    public boolean detectCommandInjection(String input) {
        if (input == null) return false;
        boolean detected = COMMAND_INJECTION_PATTERN.matcher(input).find();
        if (detected) {
            log.debug("Command injection pattern detected in input");
        }
        return detected;
    }

    /**
     * 检测路径遍历。
     */
    public boolean detectPathTraversal(String input) {
        if (input == null) return false;
        boolean detected = PATH_TRAVERSAL_PATTERN.matcher(input).find();
        if (detected) {
            log.debug("Path traversal pattern detected in input");
        }
        return detected;
    }

    /**
     * 检测敏感词，返回匹配到的敏感词列表。
     */
    public List<String> detectSensitiveWords(String input) {
        if (input == null) return List.of();

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

        /** 是否合法 */
        private boolean valid;

        /** 违规项列表 */
        private List<String> violations;

        /**
         * 是否合法（兼容 getter 风格）。
         */
        public boolean isValid() {
            return valid;
        }
    }
}
