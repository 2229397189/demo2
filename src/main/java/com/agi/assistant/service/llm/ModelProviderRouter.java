package com.agi.assistant.service.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型 provider 路由。
 * <p>
 * 依据配置 {@code llm.provider} 选择 provider，并按「缺 key / 未启用 / 名字不存在」优雅回退到 GLM：
 * <ul>
 *   <li>配置的 provider 可用 → 走它；</li>
 *   <li>配置的 provider 不可用（如 Ark 未启用）→ 记 {@code log.warn} 并回退 GLM；</li>
 *   <li>配置的 provider 名字根本不存在 → 回退 GLM；</li>
 *   <li>连 GLM 都不可用 → 抛 {@link ProviderUnavailableException}，<b>由调用方决定降级</b>，
 *       路由层不伪造回复。</li>
 * </ul>
 * 注册方式：{@code ArkModelProvider} 关闭时仍是一个合法 Bean（{@code isAvailable()=false}），
 * 而非消失的 Bean —— 因此这里统一注入 {@code List<ModelProvider>} 并建名字索引，
 * 不使用 {@code @Primary} 多 Bean 方案。
 *
 * @author Alex
 */
@Slf4j
@Service
public class ModelProviderRouter {

    /** 兜底 provider 名（GLM 永远作为最后一层降级）。 */
    public static final String FALLBACK_PROVIDER = GlmModelProvider.NAME;

    private final Map<String, ModelProvider> providerByName;

    private final String configuredProvider;

    /**
     * @param providers          容器内所有 {@link ModelProvider} Bean（glm / ark）
     * @param configuredProvider 配置的默认 provider，键 {@code llm.provider}，缺省 {@code glm}
     */
    public ModelProviderRouter(List<ModelProvider> providers,
                               @Value("${llm.provider:glm}") String configuredProvider) {
        Map<String, ModelProvider> map = new LinkedHashMap<>();
        if (providers != null) {
            for (ModelProvider provider : providers) {
                if (provider != null && provider.name() != null) {
                    map.put(provider.name(), provider);
                }
            }
        }
        this.providerByName = Collections.unmodifiableMap(map);
        this.configuredProvider = (configuredProvider == null || configuredProvider.isBlank())
                ? FALLBACK_PROVIDER
                : configuredProvider.trim();
        log.info("ModelProviderRouter 初始化：configuredProvider={}，已注册 providers={}",
                this.configuredProvider, this.providerByName.keySet());
    }

    /**
     * @return 已注册的 provider 列表（供端点列举），顺序与注入顺序一致
     */
    public List<ModelProvider> providers() {
        return List.copyOf(providerByName.values());
    }

    /**
     * @return 每个 provider 的可用性快照，{@link LinkedHashMap} 保证顺序稳定
     */
    public Map<String, Boolean> availability() {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (Map.Entry<String, ModelProvider> entry : providerByName.entrySet()) {
            result.put(entry.getKey(), entry.getValue().isAvailable());
        }
        return result;
    }

    /**
     * @return 配置的默认 provider 名（可能不可用）
     */
    public String configuredProviderName() {
        return configuredProvider;
    }

    /**
     * 返回本次调用实际会使用的 provider 名。
     *
     * @return 实际 provider 名；当配置的 provider 与 GLM 均不可用时返回 {@code null}
     */
    public String activeProviderName() {
        ModelProvider configured = providerByName.get(configuredProvider);
        if (configured != null && configured.isAvailable()) {
            return configured.name();
        }
        ModelProvider fallback = providerByName.get(FALLBACK_PROVIDER);
        if (fallback != null && fallback.isAvailable()) {
            return fallback.name();
        }
        return null;
    }

    /**
     * 发起一次 Chat 补全，自动完成 provider 选择与回退。
     *
     * @param messages    OpenAI 协议消息列表
     * @param temperature 采样温度
     * @param maxTokens   最大生成 token 数
     * @return 生成的文本内容
     * @throws ProviderUnavailableException 配置的 provider 与 GLM 均不可用时抛出
     */
    public String chat(List<Map<String, String>> messages, double temperature, int maxTokens) {
        ModelProvider provider = resolveProvider();
        log.debug("ModelProviderRouter.chat：使用 provider [{}]", provider.name());
        return provider.chat(messages, temperature, maxTokens);
    }

    /**
     * 按「配置优先、GLM 兜底」解析出实际要用的 provider。
     */
    private ModelProvider resolveProvider() {
        ModelProvider configured = providerByName.get(configuredProvider);
        if (configured == null) {
            log.warn("配置的 provider [{}] 不存在，回退到 [{}]", configuredProvider, FALLBACK_PROVIDER);
        } else if (!configured.isAvailable()) {
            log.warn("配置的 provider [{}] 不可用（缺少 api-key / model，或未启用），回退到 [{}]",
                    configuredProvider, FALLBACK_PROVIDER);
        } else {
            return configured;
        }

        ModelProvider fallback = providerByName.get(FALLBACK_PROVIDER);
        if (fallback != null && fallback.isAvailable()) {
            return fallback;
        }
        throw new ProviderUnavailableException(configuredProvider,
                "没有可用的模型 provider：配置的 [" + configuredProvider
                        + "] 不可用，回退的 [" + FALLBACK_PROVIDER + "] 也不可用");
    }
}
