package com.agi.assistant.controller;

import com.agi.assistant.config.ArkProperties;
import com.agi.assistant.config.OpenAIConfig;
import com.agi.assistant.model.vo.Result;
import com.agi.assistant.service.llm.ArkModelProvider;
import com.agi.assistant.service.llm.GlmModelProvider;
import com.agi.assistant.service.llm.ModelProviderRouter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型 Provider 管理接口。
 * <p>
 * 暴露各 provider 的可用性与当前生效 provider，供前端/运维排障。
 * <b>绝不返回 api key</b>，只返回 model 名。
 *
 * @author Alex
 */
@Slf4j
@RestController
@RequestMapping("/api/models")
@RequiredArgsConstructor
@Tag(name = "Models", description = "模型 Provider 管理接口")
public class ModelController {

    private final ModelProviderRouter modelProviderRouter;
    private final OpenAIConfig openAIConfig;
    private final ArkProperties arkProperties;

    @GetMapping("/providers")
    @Operation(summary = "模型 Provider 可用性",
            description = "列出所有模型 provider 的可用性、当前生效 provider 及其 model 名（不返回 api key）")
    public Result<Map<String, Object>> listProviders() {
        Map<String, Boolean> availability = modelProviderRouter.availability();

        List<Map<String, Object>> providerList = new ArrayList<>();
        for (Map.Entry<String, Boolean> entry : availability.entrySet()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", entry.getKey());
            info.put("available", entry.getValue());
            info.put("model", modelNameOf(entry.getKey()));
            providerList.add(info);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("configuredProvider", modelProviderRouter.configuredProviderName());
        data.put("activeProvider", modelProviderRouter.activeProviderName());
        data.put("providers", providerList);

        log.debug("查询模型 provider 可用性：{}", data);
        return Result.ok(data);
    }

    /**
     * 返回 provider 当前配置的 model 名（只暴露 model 名，绝不暴露 api key）。
     *
     * @param providerName provider 名（如 glm / ark）
     * @return 对应的 model 名；未知 provider 返回 {@code null}
     */
    private String modelNameOf(String providerName) {
        if (GlmModelProvider.NAME.equals(providerName)) {
            return openAIConfig.getModel();
        }
        if (ArkModelProvider.NAME.equals(providerName)) {
            return arkProperties.getModel();
        }
        return null;
    }
}
