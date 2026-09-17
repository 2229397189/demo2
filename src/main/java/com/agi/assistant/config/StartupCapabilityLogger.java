package com.agi.assistant.config;

import com.agi.assistant.service.llm.ModelProviderRouter;
import com.agi.assistant.service.rag.BM25Service;
import com.agi.assistant.service.rag.EmbeddingService;
import com.agi.assistant.service.rag.GraphRetrievalService;
import com.agi.assistant.service.rag.MilvusService;
import com.agi.assistant.service.security.SandboxRuntime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Supplier;

/**
 * 启动能力矩阵自检。
 * <p>
 * 应用启动完成后打印一张「能力矩阵」，让部署者一眼看出各<b>可选依赖</b>到底起没起：
 * LLM provider（glm / ark）、Embedding、Milvus、Elasticsearch、Neo4j、Kafka、Docker 沙箱。
 * 每一项输出「启用开关值 + 实际可用性」两列，并在末尾给出明确的汇总提示
 * （哪些可选组件未启用、哪些已启用但探测不可用）。
 * <p>
 * 设计约束：
 * <ul>
 *   <li><b>绝不因探测失败让应用启动失败</b>：所有探测包 try-catch（连 {@link Throwable} 都吞），
 *       异常仅记 warn，该项显示 {@code available=unknown}。</li>
 *   <li><b>探测不发起重网络请求</b>：复用各组件<b>已有的</b>可用性判断
 *       （{@code MilvusService.isAvailable()}、{@code BM25Service.isAvailable()}、
 *       {@code GraphRetrievalService.isAvailable()}、{@code SandboxRuntime.isAvailable()}、
 *       {@code ModelProviderRouter.availability()}、{@code EmbeddingService.isRemoteConfigured()}），
 *       不另造一套探测逻辑。</li>
 *   <li>各组件依赖用 {@link ObjectProvider} 注入，某个 Bean 不存在时也能安全降级为
 *       {@code available=unknown}，不会因为缺 Bean 而启动失败。</li>
 *   <li>用 SLF4J {@code log.info} 输出（每行以 {@value #PREFIX} 开头，便于脚本 grep）。</li>
 * </ul>
 *
 * @author Alex
 */
@Slf4j
@Component
@Order(100)
public class StartupCapabilityLogger implements ApplicationRunner {

    /** 每行日志前缀，供 {@code scripts/smoke.sh} 等外部脚本 grep 稳定定位。 */
    public static final String PREFIX = "[CAPABILITY] ";

    // ── 各组件依赖：ObjectProvider 注入，Bean 不存在也能安全探测 ──────────────
    private final ObjectProvider<ModelProviderRouter> modelProviderRouterProvider;
    private final ObjectProvider<EmbeddingService> embeddingServiceProvider;
    private final ObjectProvider<MilvusService> milvusServiceProvider;
    private final ObjectProvider<BM25Service> bm25ServiceProvider;
    private final ObjectProvider<GraphRetrievalService> graphRetrievalServiceProvider;
    private final ObjectProvider<SandboxRuntime> sandboxRuntimeProvider;
    private final ObjectProvider<KafkaTemplate<String, Object>> kafkaTemplateProvider;

    // ── 启用开关 / 连接串：直接读配置，用于「启用开关」列 ─────────────────────
    @Value("${llm.provider:glm}")
    private String llmProvider;

    @Value("${embedding.provider:auto}")
    private String embeddingProvider;

    @Value("${milvus.enabled:false}")
    private boolean milvusEnabled;

    @Value("${neo4j.enabled:false}")
    private boolean neo4jEnabled;

    /** Kafka 实际门控键是 spring.kafka.enabled（由 KAFKA_ENABLED 占位而来）。 */
    @Value("${spring.kafka.enabled:false}")
    private boolean kafkaEnabled;

    /** 沙箱总开关挂在 app.sandbox.enabled 下（不是沙箱模块局部的 sandbox.enabled）。 */
    @Value("${app.sandbox.enabled:true}")
    private boolean sandboxEnabled;

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String kafkaBootstrapServers;

    @Value("${spring.elasticsearch.uris:localhost:9201}")
    private String elasticsearchUris;

    @Value("${sandbox.docker.host:unix:///var/run/docker.sock}")
    private String dockerHost;

    public StartupCapabilityLogger(
            ObjectProvider<ModelProviderRouter> modelProviderRouterProvider,
            ObjectProvider<EmbeddingService> embeddingServiceProvider,
            ObjectProvider<MilvusService> milvusServiceProvider,
            ObjectProvider<BM25Service> bm25ServiceProvider,
            ObjectProvider<GraphRetrievalService> graphRetrievalServiceProvider,
            ObjectProvider<SandboxRuntime> sandboxRuntimeProvider,
            ObjectProvider<KafkaTemplate<String, Object>> kafkaTemplateProvider) {
        this.modelProviderRouterProvider = modelProviderRouterProvider;
        this.embeddingServiceProvider = embeddingServiceProvider;
        this.milvusServiceProvider = milvusServiceProvider;
        this.bm25ServiceProvider = bm25ServiceProvider;
        this.graphRetrievalServiceProvider = graphRetrievalServiceProvider;
        this.sandboxRuntimeProvider = sandboxRuntimeProvider;
        this.kafkaTemplateProvider = kafkaTemplateProvider;
    }

    @Override
    public void run(ApplicationArguments args) {
        Probe llm = probeModelProvider();
        Probe embedding = probeEmbedding();
        Probe milvus = probeMilvus();
        Probe elasticsearch = probeElasticsearch();
        Probe neo4j = probeNeo4j();
        Probe kafka = probeKafka();
        Probe docker = probeDocker();

        log.info("{}================================================================", PREFIX);
        log.info("{}AGI Assistant 启动能力矩阵 / STARTUP CAPABILITY MATRIX", PREFIX);
        log.info("{}----------------------------------------------------------------", PREFIX);
        log.info("{}组件             | 启用开关                           | 实际可用性",
                PREFIX);
        log.info("{} {}", PREFIX, row("LLM provider", "provider=" + llmProvider,
                llm.available(), llm.detail()));
        log.info("{} {}", PREFIX, row("Embedding", "provider=" + embeddingProvider,
                embedding.available(), embedding.detail()));
        log.info("{} {}", PREFIX, row("Milvus", "milvus.enabled=" + milvusEnabled,
                milvus.available(), milvus.detail()));
        log.info("{} {}", PREFIX, row("Elasticsearch", "n/a(无开关,依赖连通性)",
                elasticsearch.available(), elasticsearch.detail()));
        log.info("{} {}", PREFIX, row("Neo4j", "neo4j.enabled=" + neo4jEnabled,
                neo4j.available(), neo4j.detail()));
        log.info("{} {}", PREFIX, row("Kafka", "spring.kafka.enabled=" + kafkaEnabled,
                kafka.available(), kafka.detail()));
        log.info("{} {}", PREFIX, row("Docker Sandbox", "app.sandbox.enabled=" + sandboxEnabled,
                docker.available(), docker.detail()));
        log.info("{}----------------------------------------------------------------", PREFIX);

        // ── 汇总提示：这是排障价值最高的一行 ────────────────────────────────
        List<String> disabledOptional = new ArrayList<>();
        if (!milvusEnabled) {
            disabledOptional.add("Milvus");
        }
        if (!neo4jEnabled) {
            disabledOptional.add("Neo4j");
        }
        if (!kafkaEnabled) {
            disabledOptional.add("Kafka");
        }
        if (disabledOptional.isEmpty()) {
            log.info("{}所有可选组件（Milvus / Neo4j / Kafka）均已启用", PREFIX);
        } else {
            log.warn("{}未启用的可选组件：{} —— 相关检索/审计能力将走降级路径",
                    PREFIX, String.join(", ", disabledOptional));
        }

        List<String> enabledButDown = new ArrayList<>();
        if ("false".equals(elasticsearch.available())) {
            enabledButDown.add("Elasticsearch");
        }
        if (milvusEnabled && "false".equals(milvus.available())) {
            enabledButDown.add("Milvus");
        }
        if (neo4jEnabled && "false".equals(neo4j.available())) {
            enabledButDown.add("Neo4j");
        }
        if (kafkaEnabled && "false".equals(kafka.available())) {
            enabledButDown.add("Kafka");
        }
        if (sandboxEnabled && "false".equals(docker.available())) {
            enabledButDown.add("Docker 沙箱");
        }
        if (enabledButDown.isEmpty()) {
            log.info("{}已启用的组件探测结果均正常", PREFIX);
        } else {
            log.warn("{}已启用但探测不可用：{} —— 请确认对应中间件 / 守护进程是否已启动",
                    PREFIX, String.join(", ", enabledButDown));
        }

        if ("true".equals(llm.available())) {
            log.info("{}LLM provider 可用，对话与评测的生成链路正常", PREFIX);
        } else {
            log.warn("{}没有可用的 LLM provider（glm / ark 均不可用或缺少 key）："
                    + "对话与评测的生成能力将降级或失败", PREFIX);
        }

        if ("false".equals(embedding.available())) {
            log.warn("{}Embedding 未配置远程 key：向量化将走本地哈希降级（仅词形重叠，不具备语义泛化）",
                    PREFIX);
        }

        log.info("{}================================================================", PREFIX);
    }

    // ──────────────────────────────────────────────────────────────
    //  各组件探测
    // ──────────────────────────────────────────────────────────────

    /** LLM provider：glm / ark 各自可用性 + 当前生效 provider。 */
    private Probe probeModelProvider() {
        return guard(() -> {
            ModelProviderRouter router = modelProviderRouterProvider.getIfAvailable();
            if (router == null) {
                return new Probe("unknown", "ModelProviderRouter 未装配");
            }
            Map<String, Boolean> availability = router.availability();
            String active = router.activeProviderName();
            StringJoiner joiner = new StringJoiner(", ");
            for (Map.Entry<String, Boolean> entry : availability.entrySet()) {
                joiner.add(entry.getKey() + "=" + entry.getValue());
            }
            String detail = "active=" + (active != null ? active : "none") + ", " + joiner;
            return new Probe(active != null ? "true" : "false", detail);
        });
    }

    /** Embedding：是否配置了远程 key，未配置则走本地哈希降级。 */
    private Probe probeEmbedding() {
        return guard(() -> {
            EmbeddingService service = embeddingServiceProvider.getIfAvailable();
            if (service == null) {
                return new Probe("unknown", "EmbeddingService 未装配");
            }
            Map<String, Object> status = service.getStatus();
            Object mode = status.get("mode");
            boolean remoteConfigured = service.isRemoteConfigured();
            String detail = "mode=" + mode + ", remoteKey=" + remoteConfigured
                    + (remoteConfigured
                    ? "（远程 embedding 已配置）"
                    : "（将走本地哈希降级，仅词形重叠）");
            return new Probe(String.valueOf(remoteConfigured), detail);
        });
    }

    /** Milvus：复用 MilvusService.isAvailable()（client 是否为 null）。 */
    private Probe probeMilvus() {
        return guard(() -> {
            MilvusService service = milvusServiceProvider.getIfAvailable();
            if (service == null) {
                return new Probe("unknown", "MilvusService 未装配");
            }
            boolean available = service.isAvailable();
            String detail = milvusEnabled
                    ? (available ? "client 已连接" : "client 为 null（连接失败）")
                    : "MILVUS_ENABLED=false，Dense 召回跳过，Hybrid 退化为 ES 单路";
            return new Probe(String.valueOf(available), detail);
        });
    }

    /** Elasticsearch：复用 BM25Service.isAvailable()（轻量 ping）。 */
    private Probe probeElasticsearch() {
        return guard(() -> {
            BM25Service service = bm25ServiceProvider.getIfAvailable();
            if (service == null) {
                return new Probe("unknown", "BM25Service 未装配");
            }
            boolean available = service.isAvailable();
            String detail = "uri=" + elasticsearchUris
                    + (available ? "" : "（ping 失败，BM25 稀疏检索返回空）");
            return new Probe(String.valueOf(available), detail);
        });
    }

    /** Neo4j：复用 GraphRetrievalService.isAvailable()（driver 是否为 null）。 */
    private Probe probeNeo4j() {
        return guard(() -> {
            GraphRetrievalService service = graphRetrievalServiceProvider.getIfAvailable();
            if (service == null) {
                return new Probe("unknown", "GraphRetrievalService 未装配");
            }
            boolean available = service.isAvailable();
            String detail;
            if (neo4jEnabled) {
                detail = "driver=" + (available ? "已连接" : "null（连接失败）")
                        + ", graphExtraction=" + service.isExtractionEnabled();
            } else {
                detail = "NEO4J_ENABLED=false，图谱检索恒空";
            }
            return new Probe(String.valueOf(available), detail);
        });
    }

    /** Kafka：KafkaTemplate 是否装配（受 spring.kafka.enabled 门控）。 */
    private Probe probeKafka() {
        return guard(() -> {
            KafkaTemplate<String, Object> template = kafkaTemplateProvider.getIfAvailable();
            boolean available = template != null;
            String detail = "bootstrap=" + kafkaBootstrapServers
                    + (available ? "" : "（KafkaTemplate 未装配，审计降级为 DB + 本地日志）");
            return new Probe(String.valueOf(available), detail);
        });
    }

    /** Docker 沙箱：复用 SandboxRuntime.isAvailable()（有界的守护进程 ping）。 */
    private Probe probeDocker() {
        return guard(() -> {
            SandboxRuntime runtime = sandboxRuntimeProvider.getIfAvailable();
            if (runtime == null) {
                return new Probe("unknown", "SandboxRuntime 未装配");
            }
            boolean available = runtime.isAvailable();
            String detail = "dockerHost=" + runtime.getDockerHost()
                    + (available ? "" : "（守护进程不可达，/api/sandbox 执行不可用）");
            return new Probe(String.valueOf(available), detail);
        });
    }

    // ──────────────────────────────────────────────────────────────
    //  工具方法
    // ──────────────────────────────────────────────────────────────

    /**
     * 包裹一次探测：任何异常都吞掉并降级为 {@code available=unknown}，
     * 绝不向调用方抛出，从而保证探测失败不影响应用启动。
     */
    private Probe guard(Supplier<Probe> supplier) {
        try {
            return supplier.get();
        } catch (Throwable t) {
            log.warn("{}能力探测发生异常（不影响启动）：{}", PREFIX, t.toString());
            return new Probe("unknown", "探测异常：" + t.getMessage());
        }
    }

    /**
     * 组装一行能力矩阵：{@code 组件 | enabled=... | available=... | detail}。
     *
     * @param name      组件名（ASCII，保证列对齐）
     * @param enabled   启用开关描述
     * @param available true / false / unknown
     * @param detail    附加说明，可为空
     */
    private static String row(String name, String enabled, String available, String detail) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-16s", name)).append(" | ")
                .append(String.format("%-34s", "enabled=" + enabled)).append(" | ")
                .append(String.format("%-20s", "available=" + available));
        if (detail != null && !detail.isBlank()) {
            sb.append(" | ").append(detail);
        }
        return sb.toString();
    }

    /**
     * 单组件探测结果。
     *
     * @param available {@code "true"} / {@code "false"} / {@code "unknown"}
     * @param detail    人类可读的附加说明
     */
    private record Probe(String available, String detail) {
    }
}
