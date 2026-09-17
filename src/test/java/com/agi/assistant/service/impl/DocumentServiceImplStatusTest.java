package com.agi.assistant.service.impl;

import com.agi.assistant.mapper.DocumentChunkMapper;
import com.agi.assistant.mapper.DocumentMapper;
import com.agi.assistant.model.dto.DocumentUploadRequest;
import com.agi.assistant.model.entity.Document;
import com.agi.assistant.model.entity.DocumentChunk;
import com.agi.assistant.model.entity.ParsedDocument;
import com.agi.assistant.model.enums.DocumentStatus;
import com.agi.assistant.service.DocumentService;
import com.agi.assistant.service.rag.BM25Service;
import com.agi.assistant.service.rag.ChunkService;
import com.agi.assistant.service.rag.DocumentParser;
import com.agi.assistant.service.rag.EmbeddingService;
import com.agi.assistant.service.rag.GraphRetrievalService;
import com.agi.assistant.service.rag.MilvusService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DocumentServiceImpl} 的离线单测，覆盖两条诚信缺陷：
 * <ol>
 *   <li><b>文档状态真实性</b>：全索引成功 → COMPLETED；因组件未启用而跳过 → <b>PARTIAL</b>
 *       （绝不能是 COMPLETED）；关键步骤失败 → FAILED。
 *       —— 这是「返回 200 但检索全 0」的根因：只有 MySQL 时旧实现把文档标成 COMPLETED，
 *       实际一条索引都没建。</li>
 *   <li><b>上传自动处理 + 幂等</b>：上传后必须触发处理；重复调 process 不重复处理。</li>
 * </ol>
 * 本机无 ES / Milvus / Neo4j / Redis，全部用 Mockito mock，不发起网络与数据库访问。
 *
 * @author Alex
 */
class DocumentServiceImplStatusTest {

    @TempDir
    Path tempDir;

    private DocumentMapper documentMapper;
    private DocumentChunkMapper documentChunkMapper;
    private DocumentParser documentParser;
    private ChunkService chunkService;
    private EmbeddingService embeddingService;
    private MilvusService milvusService;
    private BM25Service bm25Service;
    private GraphRetrievalService graphRetrievalService;

    private DocumentServiceImpl service;

    /** 捕获 {@code updateDocumentResult} 的终态与说明（避免真正写库）。 */
    private final AtomicReference<DocumentStatus> capturedStatus = new AtomicReference<>();
    private final AtomicReference<String> capturedNote = new AtomicReference<>();
    private final AtomicInteger updateResultCalls = new AtomicInteger();

    /**
     * 离线场景下没有 MyBatis 启动扫描，需手动初始化实体 lambda 缓存，
     * 否则 {@code LambdaUpdateWrapper.set(Document::getStatus)} 会抛
     * 「can not find lambda cache」。仅测试脚手架，不影响生产。
     */
    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Document.class);
        TableInfoHelper.initTableInfo(assistant, DocumentChunk.class);
    }

    @BeforeEach
    void setUp() {
        documentMapper = mock(DocumentMapper.class);
        documentChunkMapper = mock(DocumentChunkMapper.class);
        documentParser = mock(DocumentParser.class);
        chunkService = mock(ChunkService.class);
        embeddingService = mock(EmbeddingService.class);
        milvusService = mock(MilvusService.class);
        bm25Service = mock(BM25Service.class);
        graphRetrievalService = mock(GraphRetrievalService.class);

        service = org.mockito.Mockito.spy(new DocumentServiceImpl(
                documentMapper, documentChunkMapper, documentParser, chunkService,
                embeddingService, milvusService, bm25Service, graphRetrievalService));

        // 拦下终态落库：记录状态与说明，避免触碰数据库
        doAnswer(inv -> {
            updateResultCalls.incrementAndGet();
            capturedStatus.set(inv.getArgument(1));
            capturedNote.set(inv.getArgument(3));
            return null;
        }).when(service).updateDocumentResult(anyLong(), any(DocumentStatus.class), anyInt(), anyString());
    }

    // ----------------------------------------------------------------
    //  纯函数：终态判定
    // ----------------------------------------------------------------

    @Test
    @DisplayName("终态判定：无跳过无失败=COMPLETED；有跳过=PARTIAL；全失败=FAILED")
    void decideFinalStatusSemantics() {
        assertThat(DocumentServiceImpl.decideFinalStatus(3, 0, 0)).isEqualTo(DocumentStatus.COMPLETED);
        // 全部因组件不可用而跳过（本地只有 MySQL 的场景）→ 必须 PARTIAL，绝不是 COMPLETED
        assertThat(DocumentServiceImpl.decideFinalStatus(0, 0, 3)).isEqualTo(DocumentStatus.PARTIAL);
        assertThat(DocumentServiceImpl.decideFinalStatus(1, 0, 1)).isEqualTo(DocumentStatus.PARTIAL);
        assertThat(DocumentServiceImpl.decideFinalStatus(2, 0, 1)).isEqualTo(DocumentStatus.PARTIAL);
        assertThat(DocumentServiceImpl.decideFinalStatus(1, 1, 0)).isEqualTo(DocumentStatus.PARTIAL);
        assertThat(DocumentServiceImpl.decideFinalStatus(0, 2, 0)).isEqualTo(DocumentStatus.FAILED);
        assertThat(DocumentServiceImpl.decideFinalStatus(0, 2, 1)).isEqualTo(DocumentStatus.FAILED);
    }

    // ----------------------------------------------------------------
    //  端到端：三种处理场景
    // ----------------------------------------------------------------

    @Test
    @DisplayName("全索引成功 → COMPLETED")
    void allIndexesSucceedYieldsCompleted() throws Exception {
        long docId = 101L;
        prepareProcessableDocument(docId);
        when(milvusService.isAvailable()).thenReturn(true);
        when(bm25Service.isAvailable()).thenReturn(true);
        when(graphRetrievalService.isAvailable()).thenReturn(true);
        when(graphRetrievalService.isExtractionEnabled()).thenReturn(true);
        when(embeddingService.embedBatch(anyList())).thenReturn(List.of(List.of(0.1f, 0.2f)));
        when(bm25Service.indexDocument(anyString(), anyString(), anyInt(), any(), anyString(), anyList()))
                .thenReturn(true);
        when(graphRetrievalService.buildGraph(anyString(), anyList())).thenReturn(3);

        service.processDocument(docId);

        assertThat(capturedStatus.get()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(capturedNote.get()).isEmpty();
    }

    @Test
    @DisplayName("【关键】组件未启用而跳过（零索引建立）→ 状态为 PARTIAL，绝不是 COMPLETED")
    void skippedIndexesYieldPartialNotCompleted() throws Exception {
        long docId = 202L;
        prepareProcessableDocument(docId);
        // 本地只有 MySQL：三路索引组件全部不可用 → 直接跳过，不尝试、不失败
        when(milvusService.isAvailable()).thenReturn(false);
        when(bm25Service.isAvailable()).thenReturn(false);
        when(graphRetrievalService.isAvailable()).thenReturn(false);

        service.processDocument(docId);

        // 这条断言直接锁死「假 COMPLETED」回归：一条索引都没建，就绝不能报成功
        assertThat(capturedStatus.get())
                .as("零索引建立时绝不能是 COMPLETED")
                .isNotEqualTo(DocumentStatus.COMPLETED);
        assertThat(capturedStatus.get()).isEqualTo(DocumentStatus.PARTIAL);
        // 说明必须结构化保留「哪些索引没建」，供前端展示
        assertThat(capturedNote.get())
                .contains("Milvus")
                .contains("Elasticsearch")
                .contains("Neo4j");
    }

    @Test
    @DisplayName("关键步骤失败（分块为空）→ FAILED")
    void keyStepFailureYieldsFailed() throws Exception {
        long docId = 303L;
        prepareProcessableDocument(docId);
        // 分块为空 → 关键步骤真失败
        when(chunkService.chunkBySemantic(anyString(), anyString())).thenReturn(List.of());

        service.processDocument(docId);

        assertThat(capturedStatus.get()).isEqualTo(DocumentStatus.FAILED);
        assertThat(capturedNote.get()).contains("分块结果为空");
    }

    // ----------------------------------------------------------------
    //  上传自动处理 + 幂等
    // ----------------------------------------------------------------

    @Test
    @DisplayName("上传后自动触发处理，且返回文档状态为 PENDING（已排队）")
    void uploadTriggersAsyncProcessing() throws Exception {
        DocumentService selfProxy = mock(DocumentService.class);
        ReflectionTestUtils.setField(service, "selfProxy", selfProxy);
        ReflectionTestUtils.setField(service, "uploadDir", tempDir.toString());

        when(documentMapper.insert(any(Document.class))).thenAnswer(inv -> {
            inv.getArgument(0, Document.class).setId(42L);
            return 1;
        });

        MockMultipartFile file = new MockMultipartFile(
                "file", "note.txt", "text/plain", "hello world".getBytes());
        DocumentUploadRequest request = new DocumentUploadRequest();
        request.setTitle("笔记");

        Document uploaded = service.uploadDocument(file, request, 1L);

        assertThat(uploaded.getId()).isEqualTo(42L);
        // 返回的文档状态 PENDING = 处理已排队（上传本身不因异步处理而报错）
        assertThat(uploaded.getStatus()).isEqualTo(DocumentStatus.PENDING.getCode());
        // 关键：上传必须触发一次处理
        verify(selfProxy).processDocument(42L);
    }

    @Test
    @DisplayName("幂等：文档已是 COMPLETED 时再次 process 被状态守卫跳过（不重复处理）")
    void duplicateProcessIsSkippedByStateGuard() throws Exception {
        long docId = 7L;
        Document doc = new Document();
        doc.setId(docId);
        doc.setUserId(1L);
        doc.setStatus(DocumentStatus.COMPLETED.getCode());
        when(documentMapper.selectById(docId)).thenReturn(doc);
        // 条件更新（CAS）未命中任何行 → 未认领为 PROCESSING
        when(documentMapper.update(any(), any())).thenReturn(0);

        service.processDocument(docId);

        verify(service, never()).updateDocumentResult(anyLong(), any(DocumentStatus.class), anyInt(), anyString());
        verify(documentChunkMapper, never()).insert(any(DocumentChunk.class));
        assertThat(updateResultCalls.get()).isZero();
    }

    // ----------------------------------------------------------------
    //  辅助
    // ----------------------------------------------------------------

    /**
     * 准备一个「可处理」（PENDING）的文档，并让解析、分块在流水线中成功，
     * 使流程能推进到三路索引阶段。
     */
    private void prepareProcessableDocument(long docId) throws Exception {
        Path file = tempDir.resolve("doc_" + docId + ".txt");
        Files.writeString(file, "这是一段用于测试的文档正文，长度足够以免被判定为空。");

        Document doc = new Document();
        doc.setId(docId);
        doc.setUserId(1L);
        doc.setTitle("测试文档");
        doc.setFilePath(file.toString());
        doc.setFileType("text");
        doc.setStatus(DocumentStatus.PENDING.getCode());
        when(documentMapper.selectById(docId)).thenReturn(doc);

        // CAS 认领成功
        when(documentMapper.update(any(), any())).thenReturn(1);

        when(documentParser.parse(anyString(), anyString()))
                .thenReturn(ParsedDocument.builder()
                        .documentId(String.valueOf(docId))
                        .cleanedContent("清洗后的正文内容")
                        .build());

        DocumentChunk chunk = new DocumentChunk();
        chunk.setContent("第一个分块的内容");
        when(chunkService.chunkBySemantic(anyString(), anyString())).thenReturn(List.of(chunk));
    }
}
