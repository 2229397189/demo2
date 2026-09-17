package com.agi.assistant.service.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 长期记忆的定期维护任务。
 * <p>
 * 背景：{@link MemoryConsolidation#decayImportance()} 与
 * {@link MemoryConsolidation#purgeExpired()} 早就写好了，但项目里从来没有调度器去调用它们 ——
 * application.yml 中的 {@code app.memory.decay-cron} / {@code app.memory.decay-enabled}
 * 两个配置项也没有任何代码消费。结果是：
 * <ul>
 *   <li>记忆重要性永不衰减，「旧的、不再被访问的记忆」始终占着召回窗口；</li>
 *   <li>TTL 到期（expires_at 已过）的记忆永远留在库里，也会被 recall 召回。</li>
 * </ul>
 * 本类把这两件事接到调度器上。
 * <p>
 * 注意：单机部署下 @Scheduled 足够；多实例部署需要换成分布式锁或调度中心，
 * 否则每个实例都会跑一遍（衰减是幂等的乘法，重复执行会让记忆加速衰减，必须避免）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.memory.decay-enabled", havingValue = "true", matchIfMissing = true)
public class MemoryMaintenanceScheduler {

    private final MemoryConsolidation memoryConsolidation;

    public MemoryMaintenanceScheduler(MemoryConsolidation memoryConsolidation) {
        this.memoryConsolidation = memoryConsolidation;
    }

    /**
     * 每日衰减 + 清理过期记忆。cron 表达式来自 {@code app.memory.decay-cron}，
     * 默认每天凌晨 3 点。
     */
    @Scheduled(cron = "${app.memory.decay-cron:0 0 3 * * ?}")
    public void runDailyMaintenance() {
        long start = System.currentTimeMillis();
        log.info("[MemoryMaintenance] 定时任务开始：重要性衰减 + 过期清理");

        try {
            memoryConsolidation.decayImportance();
        } catch (Exception e) {
            // 衰减失败不应阻塞过期清理
            log.error("[MemoryMaintenance] 重要性衰减失败: {}", e.getMessage(), e);
        }

        try {
            int purged = memoryConsolidation.purgeExpired();
            log.info("[MemoryMaintenance] 定时任务完成：清理过期记忆 {} 条，耗时 {} ms",
                    purged, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("[MemoryMaintenance] 过期记忆清理失败: {}", e.getMessage(), e);
        }
    }
}
