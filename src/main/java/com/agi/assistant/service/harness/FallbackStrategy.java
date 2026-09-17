package com.agi.assistant.service.harness;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
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

    /**
     * 执行多级降级链。
     * <p>
     * 语义：先试 {@code primary}；一旦抛出异常，则<b>按顺序</b>依次尝试 {@code tiers}
     * 中的每一项，命中第一个成功者即返回。全部失败时返回 {@code null} 并记录
     * {@code log.warn}（携带 taskName 与最后一次异常）—— <b>绝不返回编造的默认对象</b>，
     * 由调用方自行决定如何处置「无可用结果」。
     * <p>
     * 每一层的成功 / 失败都会以 {@code log.debug}/{@code log.warn} 记录，便于事后追溯
     * 降级链到底走到了哪一层。
     *
     * @param primary  主供应商（第一层）
     * @param tiers    备用供应商标（第二层及以后），按顺序尝试；可为 null 或空
     * @param taskName 任务名，用于日志定位
     * @param <T>      返回值类型
     * @return 第一个成功的结果；全部失败返回 null
     */
    public <T> T executeChain(Supplier<T> primary, List<Supplier<T>> tiers, String taskName) {
        Exception lastException = null;

        // 第一层：主供应商
        try {
            T result = primary.get();
            log.debug("FallbackChain [{}] primary supplier succeeded", taskName);
            return result;
        } catch (Exception e) {
            lastException = e;
            log.warn("FallbackChain [{}] primary supplier failed: {}", taskName, e.getMessage());
        }

        // 第二层及以后：按顺序尝试备用供应商标
        int tierCount = (tiers == null) ? 0 : tiers.size();
        for (int i = 0; i < tierCount; i++) {
            Supplier<T> tier = tiers.get(i);
            if (tier == null) {
                log.debug("FallbackChain [{}] fallback tier {} of {} is null, skipping",
                        taskName, i + 1, tierCount);
                continue;
            }
            try {
                T result = tier.get();
                log.info("FallbackChain [{}] recovered at fallback tier {} of {}",
                        taskName, i + 1, tierCount);
                return result;
            } catch (Exception e) {
                lastException = e;
                log.warn("FallbackChain [{}] fallback tier {} of {} failed: {}",
                        taskName, i + 1, tierCount, e.getMessage());
            }
        }

        log.warn("FallbackChain [{}] all {} fallback tier(s) exhausted, returning null "
                        + "(no fabricated result). lastError={}",
                taskName, tierCount, lastException == null ? "n/a" : lastException.getMessage());
        return null;
    }
}
