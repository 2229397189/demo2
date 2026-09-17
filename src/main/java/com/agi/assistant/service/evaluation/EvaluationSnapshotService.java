package com.agi.assistant.service.evaluation;

import com.agi.assistant.mapper.EvaluationResultMapper;
import com.agi.assistant.mapper.EvaluationTaskMapper;
import com.agi.assistant.model.dto.EvaluationSnapshot;
import com.agi.assistant.model.entity.EvaluationResult;
import com.agi.assistant.model.entity.EvaluationTask;
import com.agi.assistant.service.llm.ModelProviderRouter;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 评测结果快照导出服务。
 * <p>
 * 把某个评测任务的全部 {@code evaluation_result} 结果固化为<b>可提交进仓库</b>的
 * {@code .json}（机器可读、字段顺序稳定）+ {@code .md}（人读）两份快照，让简历里声称的
 * 评测数字「可被真实复现」，而不是「可被编造」。
 * <p>
 * <b>诚信约定（红线 C3）</b>：快照只写真实数值 —— 未评估指标输出 {@code null} 且
 * {@code evaluated=false}，<b>绝不</b>填 {@code 0} / {@code -1} / 其他占位数字。
 * 汇总口径复用 {@link EvaluationMetricsAggregator}（与对比页完全一致）。
 * <p>
 * <b>结构确定性</b>：所有 Map 一律 {@link LinkedHashMap}；字段顺序由 DTO 声明顺序固定；
 * {@code perQuery} 按 {@code queryId} 升序稳定排序；除 {@code meta.generatedAt} 外不含任何
 * 随机 / 时间 / 顺序不稳的内容。因此「同一个任务导出两次，结构逐字节一致」。
 *
 * @author Alex
 */
@Slf4j
@Service
public class EvaluationSnapshotService {

    /** 快照文件名中的时间戳格式。 */
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 人读 Markdown 里「未评估」的展示文案（绝不写成 0）。 */
    static final String UNAVAILABLE_LABEL = "未评估";

    /** 检索指标字段（顺序固定）。 */
    private static final List<String> RETRIEVAL_KEYS =
            List.of("recallAtK", "precisionAtK", "mrr", "ndcgAtK", "hitRate");

    /** 生成指标字段（顺序固定）。 */
    private static final List<String> GENERATION_KEYS =
            List.of("faithfulness", "answerRelevancy", "contextPrecision", "contextRecall");

    private final EvaluationTaskMapper evaluationTaskMapper;
    private final EvaluationResultMapper evaluationResultMapper;
    private final ModelProviderRouter modelProviderRouter;

    /** 业务 mapper：用于解析 {@code retrieval_metrics} / {@code generation_metrics} JSON。 */
    private final ObjectMapper objectMapper;

    /** 快照专用 mapper：显式输出 null（不受全局 NON_NULL 影响）+ 缩进，便于逐行 diff。 */
    private final ObjectMapper snapshotObjectMapper;

    /** 快照输出目录（相对仓库根或绝对路径）。 */
    private final String snapshotDir;

    public EvaluationSnapshotService(EvaluationTaskMapper evaluationTaskMapper,
                                     EvaluationResultMapper evaluationResultMapper,
                                     ModelProviderRouter modelProviderRouter,
                                     ObjectMapper objectMapper,
                                     @Value("${evaluation.snapshot.dir:docs/eval-snapshots}") String snapshotDir) {
        this.evaluationTaskMapper = evaluationTaskMapper;
        this.evaluationResultMapper = evaluationResultMapper;
        this.modelProviderRouter = modelProviderRouter;
        this.objectMapper = objectMapper;
        this.snapshotDir = (snapshotDir == null || snapshotDir.isBlank())
                ? "docs/eval-snapshots" : snapshotDir;

        // 快照专用序列化器：字段「全量输出」（含 null），并缩进。
        // 注意：全局 spring.jackson.default-property-inclusion=non_null 会把 null 字段整段抹掉，
        // 那会让「未评估」退化成「字段不存在」，故此处刻意用 ALWAYS 覆盖。
        ObjectMapper snapshotMapper = new ObjectMapper();
        snapshotMapper.setSerializationInclusion(JsonInclude.Include.ALWAYS);
        snapshotMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.snapshotObjectMapper = snapshotMapper;
    }

    // ----------------------------------------------------------------
    //  导出
    // ----------------------------------------------------------------

    /**
     * 导出指定评测任务的快照（写 json + md），并返回快照对象。
     * <p>
     * 写盘失败（目录创建 / 写文件异常）只记 {@code log.warn}，<b>不影响</b>调用方拿到快照结果。
     *
     * @param taskId   评测任务 ID（必须存在）
     * @param strategy 检索策略；为 null / 空时回退为任务自身的 {@code retrievalStrategy}
     * @return 导出的 {@link EvaluationSnapshot}
     * @throws IllegalArgumentException 任务不存在时抛出
     */
    public EvaluationSnapshot exportSnapshot(Long taskId, String strategy) {
        EvaluationTask task = evaluationTaskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("评测任务不存在: " + taskId);
        }

        String effectiveStrategy = (strategy != null && !strategy.isBlank())
                ? strategy
                : (task.getRetrievalStrategy() != null && !task.getRetrievalStrategy().isBlank()
                ? task.getRetrievalStrategy() : "UNKNOWN");

        List<EvaluationResult> results = evaluationResultMapper.selectList(
                new LambdaQueryWrapper<EvaluationResult>()
                        .eq(EvaluationResult::getTaskId, taskId));
        if (results == null) {
            results = List.of();
        }

        // 与对比页同口径：复用 EvaluationMetricsAggregator（单一实现）
        Map<String, Object> retrievalAvg =
                EvaluationMetricsAggregator.retrievalAverages(results, objectMapper);
        Map<String, Object> generationAvg =
                EvaluationMetricsAggregator.generationAverages(results, objectMapper);

        // 生效模型：真实生效的 provider 名（拿不到则为 null），不是写死的字符串
        String model = (modelProviderRouter != null) ? modelProviderRouter.activeProviderName() : null;

        // 时间戳只出现在这一处（meta.generatedAt）
        String generatedAt = LocalDateTime.now().format(TIMESTAMP_FORMATTER);

        EvaluationSnapshot snapshot = assemble(
                task, effectiveStrategy, model, results, retrievalAvg, generationAvg, generatedAt);

        writeSnapshotFiles(snapshot);
        return snapshot;
    }

    /**
     * 组装快照（<b>纯函数</b>，无 IO、无时间依赖，便于离线单测）。
     * <p>
     * 全部输入显式传入，输出对同一组输入完全确定（唯一外部变量是 {@code generatedAt}）。
     *
     * @param task         评测任务（提供 datasetId / taskId）
     * @param strategy     检索策略
     * @param model        生效模型名（可为 null）
     * @param results      评测结果行（内部按 queryId 升序稳定排序）
     * @param retrievalAvg 检索汇总（来自 {@link EvaluationMetricsAggregator}）
     * @param generationAvg 生成汇总（来自 {@link EvaluationMetricsAggregator}）
     * @param generatedAt  生成时间戳（唯一的时间来源）
     * @return 组装完成的快照
     */
    EvaluationSnapshot assemble(EvaluationTask task,
                                String strategy,
                                String model,
                                List<EvaluationResult> results,
                                Map<String, Object> retrievalAvg,
                                Map<String, Object> generationAvg,
                                String generatedAt) {
        // perQuery 按 queryId 升序稳定排序（null 视为最大，排在最后）
        List<EvaluationResult> sorted = new ArrayList<>(results == null ? List.of() : results);
        sorted.sort(Comparator.comparingLong(r -> (r == null || r.getQueryId() == null)
                ? Long.MAX_VALUE : r.getQueryId()));

        List<EvaluationSnapshot.QueryRecord> perQuery = new ArrayList<>(sorted.size());
        for (EvaluationResult r : sorted) {
            if (r == null) {
                continue;
            }
            perQuery.add(buildQueryRecord(r));
        }

        EvaluationSnapshot.Meta meta = EvaluationSnapshot.Meta.builder()
                .datasetId(task != null ? task.getDatasetId() : null)
                .strategy(strategy)
                .model(model)
                .generatedAt(generatedAt)
                .taskId(task != null ? task.getId() : null)
                .queryCount(perQuery.size())
                .build();

        EvaluationSnapshot.RetrievalSummary retrieval = evaluationSnapshotRetrieval(retrievalAvg);
        EvaluationSnapshot.GenerationSummary generation = evaluationSnapshotGeneration(generationAvg);

        return EvaluationSnapshot.builder()
                .meta(meta)
                .retrieval(retrieval)
                .generation(generation)
                .perQuery(perQuery)
                .build();
    }

    /**
     * 从检索汇总 Map 构建 {@link EvaluationSnapshot.RetrievalSummary}。
     * <p>
     * 缺失 / 未评估（聚合值为 {@code null}）的指标输出 {@code null}，且 {@code evaluated}
     * 仅在<b>至少一个</b>指标有真实值时置 true。
     */
    private EvaluationSnapshot.RetrievalSummary evaluationSnapshotRetrieval(Map<String, Object> avg) {
        return EvaluationSnapshot.RetrievalSummary.builder()
                .recallAtK(metric(avg, "recallAtK"))
                .precisionAtK(metric(avg, "precisionAtK"))
                .mrr(metric(avg, "mrr"))
                .ndcgAtK(metric(avg, "ndcgAtK"))
                .hitRate(metric(avg, "hitRate"))
                .evaluated(anyEvaluated(avg, RETRIEVAL_KEYS))
                .build();
    }

    /**
     * 从生成汇总 Map 构建 {@link EvaluationSnapshot.GenerationSummary}。
     */
    private EvaluationSnapshot.GenerationSummary evaluationSnapshotGeneration(Map<String, Object> avg) {
        return EvaluationSnapshot.GenerationSummary.builder()
                .faithfulness(metric(avg, "faithfulness"))
                .answerRelevancy(metric(avg, "answerRelevancy"))
                .contextPrecision(metric(avg, "contextPrecision"))
                .contextRecall(metric(avg, "contextRecall"))
                .evaluated(anyEvaluated(avg, GENERATION_KEYS))
                .build();
    }

    /**
     * 构建单条 query 的记录（解析其检索 / 生成指标 JSON）。
     */
    private EvaluationSnapshot.QueryRecord buildQueryRecord(EvaluationResult r) {
        Map<String, Object> retrievalMetrics = parseMetrics(r.getRetrievalMetrics());
        Map<String, Object> generationMetrics = parseMetrics(r.getGenerationMetrics());

        EvaluationSnapshot.RetrievalSummary retrieval = EvaluationSnapshot.RetrievalSummary.builder()
                .recallAtK(metric(retrievalMetrics, "recallAtK"))
                .precisionAtK(metric(retrievalMetrics, "precisionAtK"))
                .mrr(metric(retrievalMetrics, "mrr"))
                .ndcgAtK(metric(retrievalMetrics, "ndcgAtK"))
                .hitRate(metric(retrievalMetrics, "hitRate"))
                .evaluated(anyEvaluated(retrievalMetrics, RETRIEVAL_KEYS))
                .build();

        EvaluationSnapshot.GenerationSummary generation = EvaluationSnapshot.GenerationSummary.builder()
                .faithfulness(metric(generationMetrics, "faithfulness"))
                .answerRelevancy(metric(generationMetrics, "answerRelevancy"))
                .contextPrecision(metric(generationMetrics, "contextPrecision"))
                .contextRecall(metric(generationMetrics, "contextRecall"))
                .evaluated(anyEvaluated(generationMetrics, GENERATION_KEYS))
                .build();

        return EvaluationSnapshot.QueryRecord.builder()
                .queryId(r.getQueryId())
                .query(r.getQuery())
                .generatedAnswer(r.getGeneratedAnswer())
                .latencyMs(r.getLatencyMs() == null ? null : r.getLatencyMs().doubleValue())
                .retrieval(retrieval)
                .generation(generation)
                .build();
    }

    /**
     * 取出某个指标的可用数值：非数值 / 缺失 / 负值（「不可用」哨兵）一律返回 {@code null}。
     * <p>
     * <b>绝不返回 0 或 -1</b>：{@code 0.0} 是合法分数会被原样返回，只有负值才是哨兵。
     */
    private static Double metric(Map<String, Object> metrics, String key) {
        if (metrics == null) {
            return null;
        }
        Object value = metrics.get(key);
        if (!(value instanceof Number number)) {
            return null;
        }
        double d = number.doubleValue();
        return d < 0.0 ? null : d;
    }

    /**
     * 判断给定指标集合里是否<b>至少有一个</b>已完成评估（有真实数值）。
     */
    private static boolean anyEvaluated(Map<String, Object> metrics, List<String> keys) {
        for (String key : keys) {
            if (metric(metrics, key) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析指标 JSON；null / 空 / 解析失败均返回空 Map（不抛异常）。
     */
    private Map<String, Object> parseMetrics(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.debug("解析指标 JSON 失败：{}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    // ----------------------------------------------------------------
    //  落盘
    // ----------------------------------------------------------------

    /**
     * 写出快照文件（json + md）。任何失败只记 {@code log.warn}，不向上抛。
     */
    private void writeSnapshotFiles(EvaluationSnapshot snapshot) {
        String base = baseFileName(
                snapshot.getMeta().getDatasetId(),
                snapshot.getMeta().getStrategy(),
                snapshot.getMeta().getGeneratedAt());
        try {
            Path dir = Paths.get(snapshotDir);
            Files.createDirectories(dir);
            Path jsonPath = dir.resolve(base + ".json");
            Path mdPath = dir.resolve(base + ".md");
            writeUtf8(jsonPath, toStableJson(snapshot));
            writeUtf8(mdPath, toMarkdown(snapshot));
            log.info("评测快照已导出：{} + {}", jsonPath, mdPath);
        } catch (Exception e) {
            log.warn("导出评测快照失败（不影响快照对象返回）：{}", e.getMessage());
        }
    }

    private void writeUtf8(Path path, String content) throws java.io.IOException {
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 序列化为<b>字段顺序稳定</b>的 JSON（显式输出 null、缩进）。
     *
     * @param snapshot 快照
     * @return JSON 文本
     * @throws com.fasterxml.jackson.core.JsonProcessingException 序列化失败时
     */
    public String toStableJson(EvaluationSnapshot snapshot) throws com.fasterxml.jackson.core.JsonProcessingException {
        return snapshotObjectMapper.writeValueAsString(snapshot);
    }

    /**
     * 生成人读 Markdown：用表格列出汇总与逐条结果，未评估指标写「未评估」而非 0。
     */
    String toMarkdown(EvaluationSnapshot snapshot) {
        EvaluationSnapshot.Meta meta = snapshot.getMeta();
        StringBuilder sb = new StringBuilder();
        sb.append("# 评测快照\n\n");
        sb.append("| 字段 | 值 |\n|---|---|\n");
        sb.append("| 数据集 | ").append(mdCell(meta.getDatasetId())).append(" |\n");
        sb.append("| 检索策略 | ").append(mdCell(meta.getStrategy())).append(" |\n");
        sb.append("| 生效模型 | ").append(mdCell(meta.getModel())).append(" |\n");
        sb.append("| 任务 ID | ").append(meta.getTaskId()).append(" |\n");
        sb.append("| Query 数 | ").append(meta.getQueryCount()).append(" |\n");
        sb.append("| 生成时间 | ").append(mdCell(meta.getGeneratedAt())).append(" |\n\n");

        EvaluationSnapshot.RetrievalSummary r = snapshot.getRetrieval();
        sb.append("## 检索指标汇总\n\n");
        sb.append("| 指标 | 值 |\n|---|---|\n");
        sb.append("| Recall@K | ").append(fmt(r.getRecallAtK())).append(" |\n");
        sb.append("| Precision@K | ").append(fmt(r.getPrecisionAtK())).append(" |\n");
        sb.append("| MRR | ").append(fmt(r.getMrr())).append(" |\n");
        sb.append("| NDCG@K | ").append(fmt(r.getNdcgAtK())).append(" |\n");
        sb.append("| HitRate | ").append(fmt(r.getHitRate())).append(" |\n");
        sb.append("| 是否已完成评估 | ").append(r.isEvaluated() ? "是" : "否").append(" |\n\n");

        EvaluationSnapshot.GenerationSummary g = snapshot.getGeneration();
        sb.append("## 生成指标汇总\n\n");
        sb.append("| 指标 | 值 |\n|---|---|\n");
        sb.append("| Faithfulness | ").append(fmt(g.getFaithfulness())).append(" |\n");
        sb.append("| Answer Relevancy | ").append(fmt(g.getAnswerRelevancy())).append(" |\n");
        sb.append("| Context Precision | ").append(fmt(g.getContextPrecision())).append(" |\n");
        sb.append("| Context Recall | ").append(fmt(g.getContextRecall())).append(" |\n");
        sb.append("| 是否已完成评估 | ").append(g.isEvaluated() ? "是" : "否").append(" |\n\n");

        sb.append("## 逐 Query 结果\n\n");
        sb.append("| queryId | query | Recall@K | Precision@K | MRR | NDCG@K | HitRate | "
                + "Faithfulness | Answer Relevancy | Context Precision | Context Recall | latency(ms) |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (EvaluationSnapshot.QueryRecord q : snapshot.getPerQuery()) {
            EvaluationSnapshot.RetrievalSummary qr = q.getRetrieval();
            EvaluationSnapshot.GenerationSummary qg = q.getGeneration();
            sb.append("| ").append(q.getQueryId())
                    .append(" | ").append(mdCell(q.getQuery()))
                    .append(" | ").append(fmt(qr.getRecallAtK()))
                    .append(" | ").append(fmt(qr.getPrecisionAtK()))
                    .append(" | ").append(fmt(qr.getMrr()))
                    .append(" | ").append(fmt(qr.getNdcgAtK()))
                    .append(" | ").append(fmt(qr.getHitRate()))
                    .append(" | ").append(fmt(qg.getFaithfulness()))
                    .append(" | ").append(fmt(qg.getAnswerRelevancy()))
                    .append(" | ").append(fmt(qg.getContextPrecision()))
                    .append(" | ").append(fmt(qg.getContextRecall()))
                    .append(" | ").append(fmtLatency(q.getLatencyMs()))
                    .append(" |\n");
        }
        sb.append('\n');
        return sb.toString();
    }

    /**
     * 数值展示：{@code null} → 「未评估」，否则保留 4 位小数。
     */
    private static String fmt(Double value) {
        return value == null ? UNAVAILABLE_LABEL : String.format("%.4f", value);
    }

    /**
     * 延迟展示：{@code null} → 「未评估」，否则取整毫秒。
     */
    private static String fmtLatency(Double latencyMs) {
        return latencyMs == null ? UNAVAILABLE_LABEL : String.valueOf(Math.round(latencyMs));
    }

    /**
     * Markdown 单元格转义：管道符转义、换行折成空格、过长截断。
     */
    private static String mdCell(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("|", "\\|").replaceAll("\\r?\\n", " ").trim();
        return normalized.length() > 200 ? normalized.substring(0, 200) + "..." : normalized;
    }

    // ----------------------------------------------------------------
    //  文件名 / 列举
    // ----------------------------------------------------------------

    /**
     * 组装快照基础文件名：{@code {datasetId}-{strategy}-{yyyyMMdd-HHmmss}}（对非法字符做替换）。
     */
    String baseFileName(String datasetId, String strategy, String generatedAt) {
        return sanitize(datasetId) + "-" + sanitize(strategy) + "-" + sanitize(generatedAt);
    }

    /**
     * 文件名安全化：仅保留字母 / 数字 / {@code . _ -}，其余替换为 {@code _}（确定、可复现）。
     */
    private static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        return raw.trim().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * 返回快照 json / md 的相对仓库根路径（正斜杠），供接口回显。
     *
     * @param snapshot 快照
     * @return {@code {jsonPath, mdPath}}（{@link LinkedHashMap} 顺序稳定）
     */
    public Map<String, String> snapshotRelativePaths(EvaluationSnapshot snapshot) {
        String base = baseFileName(
                snapshot.getMeta().getDatasetId(),
                snapshot.getMeta().getStrategy(),
                snapshot.getMeta().getGeneratedAt());
        String dir = snapshotDir.replace('\\', '/');
        Map<String, String> paths = new LinkedHashMap<>();
        paths.put("jsonPath", dir + "/" + base + ".json");
        paths.put("mdPath", dir + "/" + base + ".md");
        return paths;
    }

    /**
     * 列出已导出的快照文件（文件名 + 大小 + 最后修改时间）；目录不存在时返回空列表。
     *
     * @return 文件信息列表，按文件名升序
     */
    public List<Map<String, Object>> listSnapshots() {
        List<Map<String, Object>> list = new ArrayList<>();
        Path dir = Paths.get(snapshotDir);
        if (!Files.isDirectory(dir)) {
            log.debug("快照目录不存在：{}", dir);
            return list;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith(".json") || name.endsWith(".md");
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .collect(Collectors.toList());
            for (Path p : files) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("fileName", p.getFileName().toString());
                info.put("size", Files.size(p));
                info.put("lastModified", Files.getLastModifiedTime(p).toInstant().toString());
                list.add(info);
            }
        } catch (Exception e) {
            log.warn("列出评测快照失败：{}", e.getMessage());
        }
        return list;
    }
}
