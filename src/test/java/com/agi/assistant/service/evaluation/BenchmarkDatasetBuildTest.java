package com.agi.assistant.service.evaluation;

import com.agi.assistant.mapper.DocumentChunkMapper;
import com.agi.assistant.mapper.DocumentMapper;
import com.agi.assistant.mapper.GoldenQueryMapper;
import com.agi.assistant.model.entity.Document;
import com.agi.assistant.model.entity.DocumentChunk;
import com.agi.assistant.model.entity.GoldenQuery;
import com.agi.assistant.model.enums.DocumentStatus;
import com.agi.assistant.service.llm.ModelProvider;
import com.agi.assistant.service.llm.ModelProviderRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link BenchmarkDataset#buildFromDocuments(String, int, boolean)} 的<b>离线单测</b>。
 * <p>
 * 不连 DB：mappers 用 Mockito stub，模型路由用内存 fake。断言四条：
 * <ol>
 *   <li>{@code relevant_doc_ids} 写的是<b>真实文档 id</b>（不是下标 / 随机数）；</li>
 *   <li>{@code expectedAnswer} 取首 chunk 并被截断（可辨）；</li>
 *   <li>{@code useLlm=false} 或 LLM 不可用时，query <b>回退为文档标题</b>；</li>
 *   <li>0 篇文档时返回 {@code 0} 且<b>不产生任何 golden query</b>。</li>
 * </ol>
 *
 * @author Alex
 */
class BenchmarkDatasetBuildTest {

    private final List<GoldenQuery> inserted = new ArrayList<>();
    private GoldenQueryMapper goldenQueryMapper;
    private DocumentMapper documentMapper;
    private DocumentChunkMapper documentChunkMapper;

    @Test
    @DisplayName("useLlm=false：query 回退为标题，relevant_doc_ids 是真实 id，expectedAnswer 被截断")
    void buildWithoutLlmUsesTitleAndRealIds() {
        Document d1 = doc(11L, "RAG 简介", DocumentStatus.COMPLETED);
        Document d2 = doc(22L, "RRF 融合原理", DocumentStatus.PARTIAL);
        DocumentChunk c1 = chunk(11L, repeat('x', 600));     // 超过 500 → 截断
        DocumentChunk c2 = chunk(22L, "第二个文档的首个分块内容");

        BenchmarkDataset ds = newDataset(
                routerOf(new FakeProvider("glm", true, "不该被用到")),
                List.of(d1, d2),
                new DocumentChunk[]{c1, c2});

        int imported = ds.buildFromDocuments("bench", 10, false);

        assertThat(imported).isEqualTo(2);
        assertThat(inserted).hasSize(2);

        GoldenQuery g1 = inserted.get(0);
        assertThat(g1.getDatasetId()).isEqualTo("bench");
        assertThat(g1.getQuery()).isEqualTo("RAG 简介");                       // 回退为标题
        assertThat(g1.getRelevantDocIds()).isEqualTo("[\"11\"]");             // 真实 id
        assertThat(g1.getExpectedAnswer()).endsWith("...");                   // 截断可辨
        assertThat(g1.getExpectedAnswer())
                .hasSize(BenchmarkDataset.EXPECTED_ANSWER_MAX_LEN + 3);

        GoldenQuery g2 = inserted.get(1);
        assertThat(g2.getRelevantDocIds()).isEqualTo("[\"22\"]");             // 真实 id
        assertThat(g2.getQuery()).isEqualTo("RRF 融合原理");
        assertThat(g2.getExpectedAnswer()).isEqualTo("第二个文档的首个分块内容"); // 未超长 → 不截断
    }

    @Test
    @DisplayName("useLlm=true 且 LLM 可用：query 用 LLM 生成的问题")
    void buildWithLlmUsesGeneratedQuestion() {
        Document d1 = doc(1L, "向量检索", DocumentStatus.COMPLETED);
        DocumentChunk c1 = chunk(1L, "向量检索通过稠密向量做语义召回……");

        BenchmarkDataset ds = newDataset(
                routerOf(new FakeProvider("glm", true, "向量检索是怎么工作的？")),
                List.of(d1),
                new DocumentChunk[]{c1});

        int imported = ds.buildFromDocuments("bench", 10, true);

        assertThat(imported).isEqualTo(1);
        assertThat(inserted.get(0).getQuery()).isEqualTo("向量检索是怎么工作的？");
        assertThat(inserted.get(0).getRelevantDocIds()).isEqualTo("[\"1\"]");
    }

    @Test
    @DisplayName("useLlm=true 但 LLM 不可用：query 回退为标题（真实、非编造）")
    void buildWithLlmUnavailableFallsBackToTitle() {
        Document d1 = doc(5L, "混合检索概览", DocumentStatus.PARTIAL);
        DocumentChunk c1 = chunk(5L, "混合检索融合稠密与稀疏……");

        // provider 不可用 → router.activeProviderName() == null
        BenchmarkDataset ds = newDataset(
                routerOf(new FakeProvider("glm", false, "不该被用到")),
                List.of(d1),
                new DocumentChunk[]{c1});

        int imported = ds.buildFromDocuments("bench", 10, true);

        assertThat(imported).isEqualTo(1);
        assertThat(inserted.get(0).getQuery()).isEqualTo("混合检索概览");
    }

    @Test
    @DisplayName("0 篇文档：返回 0 且不产生任何 golden query")
    void buildWithNoDocumentsProducesNothing() {
        BenchmarkDataset ds = newDataset(
                routerOf(new FakeProvider("glm", true, "x")),
                List.of(),
                new DocumentChunk[]{});

        int imported = ds.buildFromDocuments("bench", 10, true);

        assertThat(imported).isZero();
        assertThat(inserted).isEmpty();
    }

    // ----------------------------------------------------------------
    //  辅助
    // ----------------------------------------------------------------

    private BenchmarkDataset newDataset(ModelProviderRouter router,
                                        List<Document> docs,
                                        DocumentChunk[] firstChunks) {
        goldenQueryMapper = mock(GoldenQueryMapper.class);
        documentMapper = mock(DocumentMapper.class);
        documentChunkMapper = mock(DocumentChunkMapper.class);

        // initSampleDataset：让 selectCount>0 且已有数据用真实 id → 提前返回，不污染测试
        GoldenQuery existing = new GoldenQuery();
        existing.setDatasetId("sample-dataset");
        existing.setRelevantDocIds("[\"999\"]");
        when(goldenQueryMapper.selectCount(any())).thenReturn(1L);
        when(goldenQueryMapper.selectList(any())).thenReturn(List.of(existing));

        when(documentMapper.selectList(any())).thenReturn(docs);
        // 逐文档按顺序返回首 chunk（0 篇文档时 selectOne 不会被调用，无需 stub）
        if (firstChunks.length == 1) {
            when(documentChunkMapper.selectOne(any())).thenReturn(firstChunks[0]);
        } else if (firstChunks.length >= 2) {
            when(documentChunkMapper.selectOne(any())).thenReturn(firstChunks[0], firstChunks[1]);
        }
        when(goldenQueryMapper.insert(any(GoldenQuery.class))).thenAnswer(inv -> {
            GoldenQuery gq = inv.getArgument(0);
            inserted.add(gq);
            return 1;
        });

        return new BenchmarkDataset(new ObjectMapper(), goldenQueryMapper,
                documentMapper, documentChunkMapper, router);
    }

    private static ModelProviderRouter routerOf(ModelProvider provider) {
        return new ModelProviderRouter(List.of(provider), provider.name());
    }

    private static Document doc(long id, String title, DocumentStatus status) {
        Document d = new Document();
        d.setId(id);
        d.setTitle(title);
        d.setStatus(status.getCode());
        return d;
    }

    private static DocumentChunk chunk(long documentId, String content) {
        DocumentChunk c = new DocumentChunk();
        c.setDocumentId(documentId);
        c.setChunkIndex(0);
        c.setContent(content);
        return c;
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 内存版 {@link ModelProvider}：返回预置问题，或标记不可用。
     */
    private static final class FakeProvider implements ModelProvider {

        private final String name;
        private final boolean available;
        private final String reply;

        private FakeProvider(String name, boolean available, String reply) {
            this.name = name;
            this.available = available;
            this.reply = reply;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public String chat(List<Map<String, String>> messages, double temperature, int maxTokens) {
            return reply;
        }
    }
}
