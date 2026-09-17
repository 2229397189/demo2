package com.agi.assistant.service.impl;

import com.agi.assistant.mapper.DocumentChunkMapper;
import com.agi.assistant.mapper.DocumentMapper;
import com.agi.assistant.model.dto.DocumentUploadRequest;
import com.agi.assistant.model.entity.Document;
import com.agi.assistant.model.entity.DocumentChunk;
import com.agi.assistant.model.entity.ParsedDocument;
import com.agi.assistant.model.enums.DocumentStatus;
import com.agi.assistant.model.vo.PageResult;
import com.agi.assistant.service.DocumentService;
import com.agi.assistant.service.rag.BM25Service;
import com.agi.assistant.service.rag.ChunkService;
import com.agi.assistant.service.rag.DocumentParser;
import com.agi.assistant.service.rag.EmbeddingService;
import com.agi.assistant.service.rag.GraphRetrievalService;
import com.agi.assistant.service.rag.MilvusService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.context.annotation.Lazy;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * DocumentService implementation.
 * <p>
 * Handles document upload, storage, indexing, and retrieval pipeline.
 * The processing pipeline (parse -> chunk -> embed -> index) runs asynchronously.
 */
@Slf4j
@Service
@Lazy
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;

    /** Tika 文档解析器，支持 PDF、Word、HTML 等多种格式 */
    private final Tika tika = new Tika();
    private final DocumentParser documentParser;
    private final ChunkService chunkService;
    private final EmbeddingService embeddingService;
    private final MilvusService milvusService;
    private final BM25Service bm25Service;
    private final GraphRetrievalService graphRetrievalService;

    @Value("${app.upload.dir:./uploads}")
    private String uploadDir;

    // ----------------------------------------------------------------
    //  Upload
    // ----------------------------------------------------------------

    @Override
    public Document uploadDocument(MultipartFile file, DocumentUploadRequest request, Long userId) {
        log.info("Uploading document: user={}, fileName={}, size={}",
                userId, file.getOriginalFilename(), file.getSize());

        try {
            // 1. Save file to disk
            String fileName = generateFileName(file.getOriginalFilename());
            Path uploadPath = Paths.get(uploadDir).toAbsolutePath().normalize();
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }
            Path filePath = uploadPath.resolve(fileName);
            file.transferTo(filePath.toFile());

            // 2. Create document entity
            Document document = new Document();
            document.setUserId(userId);
            document.setTitle(request.getTitle() != null ? request.getTitle()
                    : file.getOriginalFilename());
            document.setFilePath(filePath.toString());
            document.setFileType(resolveFileType(file.getOriginalFilename()));
            document.setFileSize(file.getSize());
            document.setChunkCount(0);
            document.setStatus(DocumentStatus.PENDING.getCode());
            document.setTags(request.getTags());
            document.setSource(request.getSource());
            document.setCreatedAt(LocalDateTime.now());
            document.setUpdatedAt(LocalDateTime.now());

            documentMapper.insert(document);
            log.info("Document saved: id={}, path={}", document.getId(), filePath);

            return document;

        } catch (IOException e) {
            log.error("Failed to save uploaded file: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to save file", e);
        }
    }

    // ----------------------------------------------------------------
    //  CRUD
    // ----------------------------------------------------------------

    @Override
    public PageResult<Document> listDocuments(Long userId, int page, int size) {
        Page<Document> pageParam = new Page<>(page, size);

        LambdaQueryWrapper<Document> wrapper = new LambdaQueryWrapper<Document>()
                .eq(Document::getUserId, userId)
                .orderByDesc(Document::getCreatedAt);

        Page<Document> result = documentMapper.selectPage(pageParam, wrapper);

        return PageResult.of(result.getTotal(), result.getRecords(), page, size);
    }

    @Override
    public Document getDocument(Long id) {
        Document document = documentMapper.selectById(id);
        if (document == null) {
            throw new RuntimeException("Document not found: " + id);
        }
        return document;
    }

    @Override
    public void deleteDocument(Long id, Long userId) {
        Document document = documentMapper.selectById(id);
        if (document == null) {
            throw new RuntimeException("Document not found: " + id);
        }
        if (!document.getUserId().equals(userId)) {
            throw new RuntimeException("Access denied");
        }

        // Delete file from disk
        try {
            Path filePath = Paths.get(document.getFilePath());
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            log.warn("Failed to delete file: {}", e.getMessage());
        }

        // Delete from vector store and BM25 index
        try {
            milvusService.deleteVectors(String.valueOf(id));
            bm25Service.deleteByDocumentId(String.valueOf(id));
        } catch (Exception e) {
            log.warn("Failed to clean up indexes for document [{}]: {}", id, e.getMessage());
        }

        // Delete from DB
        documentMapper.deleteById(id);
        log.info("Deleted document [{}] for user [{}]", id, userId);
    }

    // ----------------------------------------------------------------
    //  Async Processing Pipeline
    // ----------------------------------------------------------------

    @Override
    @Async
    public void processDocument(Long id) {
        log.info("Starting document processing pipeline: docId={}", id);

        Document document = documentMapper.selectById(id);
        if (document == null) {
            log.error("Document not found for processing: {}", id);
            return;
        }

        // 索引结果统计：用于区分「全链路成功 / 部分成功 / 全链路失败」，
        // 修复「索引失败只 warn 然后无条件上报 COMPLETED」的假成功。
        List<String> degradations = new ArrayList<>();
        int okPaths = 0;
        int failedPaths = 0;
        int chunkCount = 0;

        try {
            // Update status to PROCESSING
            document.setStatus(DocumentStatus.PROCESSING.getCode());
            document.setUpdatedAt(LocalDateTime.now());
            documentMapper.updateById(document);

            // 1. Read file content based on file type
            Path filePath = Paths.get(document.getFilePath());
            String fileType = document.getFileType();

            log.info("Step 1/5: Parsing document [{}], type={}, path={}", id, fileType, filePath);

            String rawContent = resolveRawContent(filePath, fileType);

            if (rawContent == null || rawContent.isBlank()) {
                // 明确指出扫描件场景：纯图片 PDF 不含文本层，当前不支持 OCR（P2-9 遗留项）
                throw new RuntimeException("文档内容为空或无法解析"
                        + "（若是扫描件/纯图片 PDF，当前不支持 OCR 文字识别）");
            }

            String documentIdStr = String.valueOf(id);

            // 2. Parse document (clean content)
            log.info("Step 2/5: Cleaning content for document [{}], length={}", id, rawContent.length());
            ParsedDocument parsed = documentParser.parse(documentIdStr, rawContent);
            String cleanedContent = parsed.getCleanedContent();

            if (cleanedContent == null || cleanedContent.isBlank()) {
                throw new RuntimeException("文档清洗后内容为空");
            }

            // 3. Chunk document
            log.info("Step 3/5: Chunking document [{}]", id);
            List<DocumentChunk> chunks = chunkService.chunkBySemantic(documentIdStr, cleanedContent);
            chunkCount = chunks.size();
            log.info("Document [{}] chunked into {} pieces", id, chunkCount);

            if (chunks.isEmpty()) {
                throw new RuntimeException("分块结果为空");
            }

            // 4. 分块落库（向量 ID 与分块下标一一对应，供各路索引复用）
            List<String> milvusIds = new ArrayList<>();
            List<String> documentIds = new ArrayList<>();
            List<Long> chunkIndices = new ArrayList<>();
            List<String> chunkContents = new ArrayList<>();

            for (int i = 0; i < chunks.size(); i++) {
                DocumentChunk chunk = chunks.get(i);
                String chunkId = documentIdStr + "_" + i;
                milvusIds.add(chunkId);
                documentIds.add(documentIdStr);
                chunkIndices.add((long) i);
                chunkContents.add(chunk.getContent() == null ? "" : chunk.getContent());

                chunk.setVectorId(chunkId);
                chunk.setDocumentId(id);
                chunk.setCreatedAt(LocalDateTime.now());
            }

            documentChunkMapper.delete(new LambdaQueryWrapper<DocumentChunk>()
                    .eq(DocumentChunk::getDocumentId, id));
            for (DocumentChunk chunk : chunks) {
                documentChunkMapper.insert(chunk);
            }
            log.info("Step 4/5: Persisted {} chunks for document [{}]", chunks.size(), id);

            // ── 5. 三路索引：向量 / BM25 / 图谱 ────────────────────────
            log.info("Step 5/5: Indexing document [{}] into vector store, BM25 and knowledge graph", id);

            // 5.1 稠密向量（Milvus）
            if (!milvusService.isAvailable()) {
                degradations.add("Milvus 未启用，未建立向量索引");
            } else {
                try {
                    List<List<Float>> embeddings = embeddingService.embedBatch(chunkContents);
                    if (embeddings == null || embeddings.size() != chunks.size()) {
                        throw new IllegalStateException("embedding 数量与分块数量不一致: got "
                                + (embeddings == null ? 0 : embeddings.size()) + ", expect " + chunks.size());
                    }
                    if (embeddings.stream().anyMatch(v -> v == null || v.isEmpty())) {
                        throw new IllegalStateException("存在空 embedding，拒绝写入向量库");
                    }
                    milvusService.insertVectors(milvusIds, documentIds, chunkIndices, chunkContents, embeddings);
                    okPaths++;
                    log.info("Dense index written for document [{}]: {} vectors", id, embeddings.size());
                } catch (Exception e) {
                    failedPaths++;
                    degradations.add("向量索引失败: " + e.getMessage());
                    log.error("Dense indexing failed for document [{}]: {}", id, e.getMessage(), e);
                }
            }

            // 5.2 稀疏关键词（Elasticsearch BM25）
            if (!bm25Service.isAvailable()) {
                degradations.add("Elasticsearch 不可用，未建立 BM25 索引");
            } else {
                boolean sparseOk = true;
                String failureReason = null;
                try {
                    List<String> tags = document.getTags() != null
                            ? List.of(document.getTags().split(",")) : List.of();
                    for (int i = 0; i < chunks.size(); i++) {
                        if (!bm25Service.indexDocument(milvusIds.get(i), documentIdStr, i,
                                document.getTitle(), chunkContents.get(i), tags)) {
                            sparseOk = false;
                        }
                    }
                } catch (Exception e) {
                    sparseOk = false;
                    failureReason = e.getMessage();
                }
                if (sparseOk) {
                    okPaths++;
                    log.info("BM25 index written for document [{}]: {} chunks", id, chunks.size());
                } else {
                    failedPaths++;
                    degradations.add("BM25 索引失败" + (failureReason != null ? ": " + failureReason : "（部分分块写入失败）"));
                    log.error("BM25 indexing failed for document [{}]", id);
                }
            }

            // 5.3 知识图谱（Neo4j）：实体抽取 + 建块节点 + MENTIONS 边
            if (!graphRetrievalService.isAvailable()) {
                degradations.add("Neo4j 未启用，未构建知识图谱");
            } else if (!graphRetrievalService.isExtractionEnabled()) {
                degradations.add("图谱构建已关闭（rag.graph-extraction.enabled=false）");
            } else {
                try {
                    int mentions = graphRetrievalService.buildGraph(documentIdStr, chunks);
                    okPaths++;
                    log.info("Knowledge graph built for document [{}]: {} mention edges", id, mentions);
                } catch (Exception e) {
                    failedPaths++;
                    degradations.add("知识图谱构建失败: " + e.getMessage());
                    log.error("Graph build failed for document [{}]: {}", id, e.getMessage(), e);
                }
            }

            // 6. 依据各路径结果决定最终状态
            DocumentStatus finalStatus;
            if (failedPaths == 0) {
                finalStatus = DocumentStatus.COMPLETED;
            } else if (okPaths == 0) {
                finalStatus = DocumentStatus.FAILED;
            } else {
                finalStatus = DocumentStatus.PARTIAL;
            }

            updateDocumentResult(id, finalStatus, chunkCount, String.join("; ", degradations));
            log.info("Document [{}] processing finished: status={}, chunks={}, okPaths={}, failedPaths={}, notes={}",
                    id, finalStatus, chunkCount, okPaths, failedPaths, degradations);

        } catch (Exception e) {
            log.error("Document [{}] processing failed: {}", id, e.getMessage(), e);
            updateDocumentResult(id, DocumentStatus.FAILED, chunkCount, "处理失败: " + e.getMessage());
        }
    }

    // ----------------------------------------------------------------
    //  Internal Methods
    // ----------------------------------------------------------------

    /**
     * 按文件类型读取原始文本。
     * <p>
     * markdown / text 直接按 UTF-8 读；其余格式（pdf / word / html / unknown）统一走 Tika。
     * <p>
     * 修复点：旧实现里只有 pdf/word/unknown 会走 Tika，html 落到 else 分支被当作纯文本读，
     * 结果整篇 HTML 标签进入分块与索引。
     */
    private String resolveRawContent(Path filePath, String fileType) throws IOException {
        if ("markdown".equals(fileType) || "text".equals(fileType)) {
            return Files.readString(filePath);
        }
        return parseWithTika(filePath);
    }

    /**
     * 统一更新文档的处理结果。
     * <p>
     * 用显式 set() 而不是 updateById(entity)，这样空串也能写入（清掉上一次的错误说明），
     * 不依赖 MyBatis-Plus 的字段策略。若 error_message 列尚未迁移到旧库，
     * 自动降级为只更新状态，保证流水线本身不会因此失败。
     */
    private void updateDocumentResult(Long id, DocumentStatus status, int chunkCount, String note) {
        String raw = note == null ? "" : note;
        final String message = raw.length() > 1000 ? raw.substring(0, 1000) : raw;

        try {
            documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                    .eq(Document::getId, id)
                    .set(Document::getStatus, status.getCode())
                    .set(Document::getChunkCount, chunkCount)
                    .set(Document::getErrorMessage, message)
                    .set(Document::getUpdatedAt, LocalDateTime.now()));
        } catch (Exception e) {
            log.warn("更新文档 [{}] 状态（含 error_message）失败，退回仅更新状态字段: {}", id, e.getMessage());
            try {
                documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                        .eq(Document::getId, id)
                        .set(Document::getStatus, status.getCode())
                        .set(Document::getChunkCount, chunkCount)
                        .set(Document::getUpdatedAt, LocalDateTime.now()));
            } catch (Exception ex) {
                log.error("更新文档 [{}] 状态失败: {}", id, ex.getMessage(), ex);
            }
        }
    }

    private String generateFileName(String originalName) {
        String extension = "";
        if (originalName != null && originalName.contains(".")) {
            extension = originalName.substring(originalName.lastIndexOf('.'));
        }
        return UUID.randomUUID().toString().replace("-", "") + extension;
    }

    private String resolveFileType(String fileName) {
        if (fileName == null) return "unknown";
        if (fileName.endsWith(".md")) return "markdown";
        if (fileName.endsWith(".txt")) return "text";
        if (fileName.endsWith(".pdf")) return "pdf";
        if (fileName.endsWith(".doc") || fileName.endsWith(".docx")) return "word";
        if (fileName.endsWith(".html") || fileName.endsWith(".htm")) return "html";
        return "unknown";
    }

    /**
     * 使用 Apache Tika 解析文档，支持 Word、HTML 等多种格式。
     * PDF 文件使用 PDFBox 解析以获得更好的中文支持。
     *
     * @param filePath 文件路径
     * @return 提取的文本内容
     */
    private String parseWithTika(Path filePath) {
        String fileName = filePath.getFileName().toString().toLowerCase();

        // PDF 文件使用 PDFBox 解析，解决中文乱码问题
        if (fileName.endsWith(".pdf")) {
            return parsePdfWithPdfBox(filePath);
        }

        // 其他格式使用 Tika
        try (InputStream inputStream = Files.newInputStream(filePath)) {
            String content = tika.parseToString(inputStream);
            log.info("Tika parsed file [{}], extracted {} characters", filePath.getFileName(), content.length());
            return content;
        } catch (IOException | TikaException e) {
            log.error("Tika failed to parse file [{}]: {}", filePath, e.getMessage(), e);
            throw new RuntimeException("文档解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 使用 Apache PDFBox 解析 PDF 文件，提供更好的中文支持。
     * <p>
     * 修复说明（P2-9）：此前 {@code PDDocument.load(File)} 会把整个 PDF
     * （含页面位图等中间结构）全部读进堆内存，大文件直接 OOM。
     * 现在使用 {@code MemoryUsageSetting.setupMixed(16MB)}：
     * 超过 16MB 的解析中间态自动落到临时文件，堆内存占用有上界。
     *
     * @param filePath PDF 文件路径
     * @return 提取的文本内容
     */
    private String parsePdfWithPdfBox(Path filePath) {
        try (PDDocument document = PDDocument.load(
                filePath.toFile(), MemoryUsageSetting.setupMixed(16 * 1024 * 1024))) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);  // 按位置排序，保持阅读顺序
            String content = stripper.getText(document);

            // 清理空字符和多余的空白
            content = cleanPdfContent(content);

            log.info("PDFBox parsed file [{}], extracted {} characters, pages={}",
                    filePath.getFileName(), content.length(), document.getNumberOfPages());
            return content;
        } catch (IOException e) {
            log.error("PDFBox failed to parse file [{}]: {}", filePath, e.getMessage(), e);
            throw new RuntimeException("PDF 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 清理 PDF 解析后的内容，移除空字符和多余空白。
     *
     * @param content 原始解析内容
     * @return 清理后的内容
     */
    private String cleanPdfContent(String content) {
        if (content == null) {
            return "";
        }
        // 移除空字符
        content = content.replace("\0", "");
        // 移除零宽空格和其他不可见 Unicode 字符
        content = content.replaceAll("[\\u200B\\u200C\\u200D\\uFEFF]", "");
        // 规范化空白：将多个连续空格/制表符合并为单个空格
        content = content.replaceAll("[ \\t]+", " ");
        // 规范化换行：移除多余的空行
        content = content.replaceAll("\\n{3,}", "\n\n");
        // 清理域名中的异常空格（PDF 解析常见问题）
        content = content.replaceAll("\\.\\s+com", ".com");
        content = content.replaceAll("\\.\\s+cn", ".cn");
        content = content.replaceAll("\\.\\s+org", ".org");
        content = content.replaceAll("\\.\\s+net", ".net");
        // 清理数字和日期中的异常空格（如 "2. 09" -> "2.09", "2023. 09" -> "2023.09"）
        content = content.replaceAll("(\\d)\\s+\\.(\\s+)(\\d)", "$1.$3");
        content = content.replaceAll("(\\d{4})\\s+\\.(\\s+)(\\d{2})", "$1.$3");
        // 清理常见模式的异常空格
        content = content.replaceAll("(\\d+)\\s+%", "$1%");
        content = content.replaceAll("(\\d+)\\s+s\\b", "$1s");
        return content.trim();
    }
}
