package com.agi.assistant.service.security;

import com.agi.assistant.model.enums.ToolRiskLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ToolRiskClassifier} 测试。
 * <p>
 * 关注两条容易被用错的分支：
 * <ol>
 *   <li>已注册工具不能走 {@code classify(name, params)} —— 「未知名称一律 WARN」
 *       会把所有自研工具都误判成 WARN，所以新增了
 *       {@link ToolRiskClassifier#isBlockedName(String)} + {@code classifyParamsOnly}。</li>
 *   <li>参数里的危险内容要能把风险等级往上抬（安全工具 → WARN，警告工具 → BLOCK）。</li>
 * </ol>
 */
class ToolRiskClassifierTest {

    private final ToolRiskClassifier classifier = new ToolRiskClassifier();

    // ──────────────────────────────────────────────────────────────
    //  名称分类
    // ──────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "高危工具名应阻断: {0}")
    @ValueSource(strings = {"delete", "drop", "truncate", "exec", "eval", "sudo", "rm", "shutdown"})
    @DisplayName("黑名单工具名 → BLOCK")
    void blockListedNamesAreBlocked(String name) {
        assertThat(classifier.classify(name)).isEqualTo(ToolRiskLevel.BLOCK);
        assertThat(classifier.isBlockedName(name)).isTrue();
    }

    @ParameterizedTest(name = "安全工具名应放行: {0}")
    @ValueSource(strings = {"search", "query", "read", "list", "calculate", "summarize"})
    @DisplayName("白名单工具名 → SAFE")
    void safeNamesAreSafe(String name) {
        assertThat(classifier.classify(name)).isEqualTo(ToolRiskLevel.SAFE);
        assertThat(classifier.isBlockedName(name)).isFalse();
    }

    @ParameterizedTest(name = "写操作工具名应告警: {0}")
    @ValueSource(strings = {"write", "update", "insert", "upload", "send", "create"})
    @DisplayName("警告级工具名 → WARN")
    void warnNamesAreWarn(String name) {
        assertThat(classifier.classify(name)).isEqualTo(ToolRiskLevel.WARN);
    }

    @Test
    @DisplayName("大小写与空白不敏感")
    void nameMatchingIsNormalized() {
        assertThat(classifier.classify("DELETE")).isEqualTo(ToolRiskLevel.BLOCK);
        assertThat(classifier.classify("  Search  ")).isEqualTo(ToolRiskLevel.SAFE);
        assertThat(classifier.isBlockedName("  DROP ")).isTrue();
    }

    @Test
    @DisplayName("未知工具名默认 WARN，空名称也 WARN，null 名称不会 NPE")
    void unknownAndEmptyNamesDefaultToWarn() {
        assertThat(classifier.classify("myCustomTool")).isEqualTo(ToolRiskLevel.WARN);
        assertThat(classifier.classify("")).isEqualTo(ToolRiskLevel.WARN);
        assertThat(classifier.classify((String) null)).isEqualTo(ToolRiskLevel.WARN);
        assertThat(classifier.isBlockedName(null)).isFalse();
        assertThat(classifier.isBlockedName("")).isFalse();
    }

    // ──────────────────────────────────────────────────────────────
    //  参数升级
    // ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("安全工具携带危险参数 → 升级为 WARN")
    void safeToolWithDangerousParamsIsUpgraded() {
        assertThat(classifier.classify("search", "drop table users"))
                .isEqualTo(ToolRiskLevel.WARN);
        assertThat(classifier.classify("search", "rm -rf /"))
                .isEqualTo(ToolRiskLevel.WARN);
    }

    @Test
    @DisplayName("警告工具携带危险参数 → 升级为 BLOCK")
    void warnToolWithDangerousParamsIsBlocked() {
        assertThat(classifier.classify("write", "drop table users"))
                .isEqualTo(ToolRiskLevel.BLOCK);
        assertThat(classifier.classify("upload", "powershell.exe -c calc"))
                .isEqualTo(ToolRiskLevel.BLOCK);
    }

    @Test
    @DisplayName("已注册工具走 classifyParamsOnly：基础风险由工具自己声明")
    void classifyParamsOnlyIgnoresToolName() {
        // 自研工具名两边都不在白/黑名单里，用 classify 会被误判成 WARN
        assertThat(classifier.classify("knowledge_search")).isEqualTo(ToolRiskLevel.WARN);
        // 而按参数侧判定则是干净的安全态
        assertThat(classifier.classifyParamsOnly("{\"query\":\"什么是 RAG\"}"))
                .isEqualTo(ToolRiskLevel.SAFE);

        assertThat(classifier.classifyParamsOnly(null)).isEqualTo(ToolRiskLevel.SAFE);
        assertThat(classifier.classifyParamsOnly("")).isEqualTo(ToolRiskLevel.SAFE);
        assertThat(classifier.classifyParamsOnly("   ")).isEqualTo(ToolRiskLevel.SAFE);
        assertThat(classifier.classifyParamsOnly("delete from users"))
                .isEqualTo(ToolRiskLevel.WARN);
        assertThat(classifier.classifyParamsOnly("exec(" ))
                .isEqualTo(ToolRiskLevel.WARN);
    }

    @Test
    @DisplayName("名称命中硬红线时，即使工具已注册也不放行")
    void blockedNameIsAHardConstraint() {
        // isBlockedName 不依赖注册状态：误注册的高危工具也不会被放行
        assertThat(classifier.isBlockedName("delete")).isTrue();
        assertThat(classifier.classifyParamsOnly("harmless params"))
                .isEqualTo(ToolRiskLevel.SAFE);
        // 两者组合（注册表里的判定方式）应得出 BLOCK
        ToolRiskLevel effective = classifier.isBlockedName("delete")
                ? ToolRiskLevel.BLOCK
                : classifier.classifyParamsOnly("harmless params");
        assertThat(effective).isEqualTo(ToolRiskLevel.BLOCK);
    }

    // ──────────────────────────────────────────────────────────────
    //  敏感数据识别
    // ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("敏感数据模式识别")
    void sensitiveDataDetection() {
        assertThat(classifier.containsSensitiveData("{\"password\":\"x\"}")).isTrue();
        assertThat(classifier.containsSensitiveData("api_key=abc123")).isTrue();
        assertThat(classifier.containsSensitiveData("jdbc:mysql://localhost/db")).isTrue();
        assertThat(classifier.containsSensitiveData("redis://127.0.0.1:6379")).isTrue();
        assertThat(classifier.containsSensitiveData("什么是快速排序")).isFalse();
        assertThat(classifier.containsSensitiveData(null)).isFalse();
    }
}
