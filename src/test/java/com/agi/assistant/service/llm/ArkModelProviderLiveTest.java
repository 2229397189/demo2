package com.agi.assistant.service.llm;

import com.agi.assistant.config.ArkProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Ark（火山方舟）真实联调测试 —— <b>会真实发起网络请求、消耗真实额度</b>。
 * <p>
 * 为「不污染默认 {@code mvn test} 结果」采用两层防护：
 * <ol>
 *   <li>类名匹配 surefire 排除规则 {@code **}{@code /*LiveTest.java}（见 pom.xml），
 *       默认测试阶段<b>不会</b>执行本类；</li>
 *   <li>{@link Assumptions#assumeTrue} 兜底：缺 key 或 {@code ARK_ENABLED != true} 时自动 skip（不失败）。</li>
 * </ol>
 * 手动运行（surefire 的 {@code -Dtest} 会覆盖 excludes）：
 * <pre>
 * mvn -q test -Dtest=ArkModelProviderLiveTest -DfailIfNoTests=false
 * </pre>
 * key / base-url / model 优先取环境变量，缺失时回退解析项目根目录的 {@code .env}。
 * <p>
 * 注意：本测试直接 {@code new ArkModelProvider(props, new ObjectMapper())}，
 * <b>不启动整个 Spring 上下文</b>（避免依赖 MySQL）。
 *
 * @author Alex
 */
class ArkModelProviderLiveTest {

    @Test
    @Timeout(120)
    @DisplayName("真实调用 Ark doubao 接入点，返回非空内容")
    void arkChatReturnsNonEmpty() {
        String apiKey = resolve("ARK_API_KEY");
        String baseUrl = resolveOrDefault("ARK_BASE_URL", "https://ark.cn-beijing.volces.com/api/v3");
        String model = resolve("ARK_MODEL");
        boolean enabled = "true".equalsIgnoreCase(resolveOrDefault("ARK_ENABLED", "false"));

        assumeTrue(enabled && apiKey != null && !apiKey.isBlank() && model != null && !model.isBlank(),
                "未提供 ARK_API_KEY / ARK_MODEL 或 ARK_ENABLED != true，跳过 Ark 真实联调");

        ArkProperties props = new ArkProperties();
        props.setEnabled(true);
        props.setBaseUrl(baseUrl);
        props.setApiKey(apiKey);
        props.setModel(model);
        props.setTimeout(120);
        props.setConnectTimeout(30);

        ArkModelProvider provider = new ArkModelProvider(props, new ObjectMapper());
        assertThat(provider.isAvailable()).as("构造后应判定为可用").isTrue();

        String reply = provider.chat(
                List.of(Map.of("role", "user", "content", "只回复两个字：收到")), 0.0, 16);

        System.out.println("[ArkLiveTest] baseUrl=" + baseUrl);
        System.out.println("[ArkLiveTest] model=" + model);
        System.out.println("[ArkLiveTest] reply=[" + reply + "]");

        assertThat(reply).isNotNull().isNotBlank();
    }

    private static String resolve(String key) {
        String fromEnv = System.getenv(key);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        return loadDotEnv().get(key);
    }

    private static String resolveOrDefault(String key, String defaultValue) {
        String value = resolve(key);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }

    /**
     * 极简 {@code .env} 解析（仅用于测试取 key，不参与生产逻辑）。
     */
    private static Map<String, String> loadDotEnv() {
        Map<String, String> map = new HashMap<>();
        Path envPath = Paths.get(".env");
        if (!Files.exists(envPath)) {
            return map;
        }
        try {
            for (String rawLine : Files.readAllLines(envPath, StandardCharsets.UTF_8)) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                    continue;
                }
                if (line.startsWith("export ")) {
                    line = line.substring("export ".length()).trim();
                }
                int idx = line.indexOf('=');
                String key = line.substring(0, idx).trim();
                String value = line.substring(idx + 1).trim();
                if (value.length() >= 2
                        && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                map.putIfAbsent(key, value);
            }
        } catch (IOException e) {
            System.out.println("[ArkLiveTest] 读取 .env 失败：" + e.getMessage());
        }
        return map;
    }
}
