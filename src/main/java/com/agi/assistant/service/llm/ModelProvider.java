package com.agi.assistant.service.llm;

import java.util.List;
import java.util.Map;

/**
 * 模型 provider 抽象。
 * <p>
 * 由 {@code ModelProviderRouter} 按配置（{@code llm.provider}）选择、缺 key 时回退。
 * 实现约定：{@link #isAvailable()} 只读配置、**不触发网络**；构造期不建连。
 *
 * @author Alex
 */
public interface ModelProvider {

    /**
     * provider 名称，如 {@code "glm"} / {@code "ark"}。
     */
    String name();

    /**
     * 可用性 gate：如 Ark 需 {@code ark.enabled=true} 且 api-key 非空。
     * <p>
     * 该判定只读本地配置，绝不发起网络请求。
     */
    boolean isAvailable();

    /**
     * 发起一次 Chat 补全。
     *
     * @param messages    OpenAI 协议消息列表，每项形如 {@code {"role": "...", "content": "..."}}
     * @param temperature 采样温度
     * @param maxTokens   最大生成 token 数
     * @return 生成的文本内容
     */
    String chat(List<Map<String, String>> messages, double temperature, int maxTokens);
}
