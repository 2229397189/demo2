package com.agi.assistant.service.harness;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 降级策略
 * <p>
 * 主策略失败后自动切换到备用策略，支持优雅降级：
 * <ul>
 *   <li>主策略执行失败时，切换到备用策略</li>
 *   <li>备用策略也失败时，返回降级响应</li>
 *   <li>记录失败日志，不影响其他任务执行</li>
 * </ul>
 */
@Slf4j
@Component
public class FallbackStrategy {

    /**
     * 使用主策略执行，失败时回退到备用策略。
     * <p>
     * 若主策略正常返回则直接使用结果；
     * 若主策略抛出异常则执行备用策略；
     * 若备用策略也失败，返回 null 并记录错误。
     *
     * @param primary  主策略
     * @param fallback 备用策略
     * @param <T>      返回值类型
     * @return 主策略或备用策略的执行结果；若均失败则返回 null
     */
    public <T> T executeWithFallback(Supplier<T> primary, Supplier<T> fallback) {
        try {
            T result = primary.get();
            log.debug("Primary strategy succeeded");
            return result;
        } catch (Exception e) {
            log.warn("Primary strategy failed: {}, switching to fallback", e.getMessage());
            try {
                T fallbackResult = fallback.get();
                log.info("Fallback strategy succeeded");
                return fallbackResult;
            } catch (Exception fallbackEx) {
                log.error("Fallback strategy also failed: {}", fallbackEx.getMessage(), fallbackEx);
                return null;
            }
        }
    }

    /**
     * 生成降级响应。
     * <p>
     * 当主策略和备用策略均失败时，返回一个带有失败原因的降级响应对象。
     *
     * @param reason 降级原因描述
     * @return 降级响应字符串
     */
    public String degradedResponse(String reason) {
        log.warn("Returning degraded response: {}", reason);
        return "[降级响应] " + reason + " - 系统正在执行降级处理，请稍后重试或联系管理员。";
    }

    /**
     * 优雅降级执行器。
     * <p>
     * 执行给定任务，若失败则记录日志并返回降级结果，不抛出异常，
     * 确保不会阻断其他任务的执行。
     *
     * @param task           要执行的任务
     * @param degradedResult 降级时的默认返回值
     * @param taskName       任务名称，用于日志记录
     * @param <T>            返回值类型
     * @return 任务执行结果或降级默认值
     */
    public <T> T gracefulDegrade(Supplier<T> task, T degradedResult, String taskName) {
        try {
            return task.get();
        } catch (Exception e) {
            log.error("Task [{}] failed with graceful degradation: {}", taskName, e.getMessage(), e);
            return degradedResult;
        }
    }
}
