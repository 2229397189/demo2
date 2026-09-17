package com.agi.assistant.service.llm;

/**
 * 模型 provider 不可用异常。
 * <p>
 * 由 {@link ModelProviderRouter} 在「配置的 provider 不可用、且回退的 GLM 也不可用」时抛出，
 * 交由调用方决定降级策略（例如返回兜底文案、走离线路径或直接报错）。
 * 路由层自身绝不伪造回复内容。
 *
 * @author Alex
 */
public class ProviderUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 触发本次失败的 provider 名称（可空）。 */
    private final String providerName;

    /**
     * @param providerName 触发失败的 provider 名称（可空）
     * @param message      可读的失败原因
     */
    public ProviderUnavailableException(String providerName, String message) {
        super(message);
        this.providerName = providerName;
    }

    /**
     * @param message 可读的失败原因
     */
    public ProviderUnavailableException(String message) {
        super(message);
        this.providerName = null;
    }

    /**
     * @return 触发失败的 provider 名称；无则返回 {@code null}
     */
    public String getProviderName() {
        return providerName;
    }
}
