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
import com.agi.assistant.service.rag.MilvusService;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.context.annotation.Lazy;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import java.util.stream.Collectors;

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

        try {
            // Update status to PROCESSING
            document.setStatus(DocumentStatus.PROCESSING.getCode());
            document.setUpdatedAt(LocalDateTime.now());
            documentMapper.updateById(document);

            // 1. Read file content based on file type
            Path filePath = Paths.get(document.getFilePath());
            String fileType = document.getFileType();
            String rawContent;

            log.info("Step 1/4: Parsing document [{}], type={}, path={}", id, fileType, filePath);

            if ("markdown".equals(fileType) || "text".equals(fileType)) {
                // Markdown 和纯文本直接读取
                rawContent = Files.readString(filePath);
            } else if ("pdf".equals(fileType) || "word".equals(fileType) || "unknown".equals(fileType)) {
                // 使用 Tika 解析 PDF、Word、HTML 等格式
                rawContent = parseWithTika(filePath);
            } else {
                // 尝试作为文本读取
                rawContent = Files.readString(filePath);
            }

            if (rawContent == null || rawContent.isBlank()) {
                throw new RuntimeException("文档内容为空或无法解析");
            }

            String documentIdStr = String.valueOf(id);

            // 2. Parse document (clean content)
            log.info("Step 2/4: Cleaning content for document [{}], length={}", id, rawContent.length());
            ParsedDocument parsed = documentParser.parse(documentIdStr, rawContent);
            String cleanedContent = parsed.getCleanedContent();

            if (cleanedContent == null || cleanedContent.isBlank()) {
                throw new RuntimeException("文档清洗后内容为空");
            }

            // 3. Chunk document
            log.info("Step 2/4: Chunking document [{}]", id);
            List<DocumentChunk> chunks = chunkService.chunkBySemantic(documentIdStr, cleanedContent);
            log.info("Document [{}] chunked into {} pieces", id, chunks.size());

            // 4. Generate embeddings (batch)
            log.info("Step 3/4: Generating embeddings for [{}], {} chunks", id, chunks.size());
            List<String> chunkContents = chunks.stream()
                    .map(DocumentChunk::getContent)
                    .collect(Collectors.toList());

            // 使用批量 embedding 提高性能
            List<List<Float>> embeddings = List.of();
            if (milvusService.isAvailable()) {
                try {
                    embeddings = embeddingService.embedBatch(chunkContents);
                    log.info("Generated {} embeddings for document [{}]", embeddings.size(), id);
                } catch (Exception e) {
                    log.warn("Embedding generation failed for document [{}], vector index will be skipped: {}",
                            id, e.getMessage());
                }
            } else {
                log.info("Milvus is disabled, skipping embeddings for document [{}]", id);
            }

            // 5. Index into Milvus
            log.info("Step 4/4: Indexing document [{}] into vector store and BM25", id);
            List<String> milvusIds = new ArrayList<>();
            List<String> documentIds = new ArrayList<>();
            List<Long> chunkIndices = new ArrayList<>();

            for (int i = 0; i < chunks.size(); i++) {
                DocumentChunk chunk = chunks.get(i);
                String chunkId = documentIdStr + "_" + i;
                milvusIds.add(chunkId);
                documentIds.add(documentIdStr);
                chunkIndices.add((long) i);

                chunk.setVectorId(chunkId);
                chunk.setDocumentId(id);
                chunk.setCreatedAt(LocalDateTime.now());
            }

            documentChunkMapper.delete(new LambdaQueryWrapper<DocumentChunk>()
                    .eq(DocumentChunk::getDocumentId, id));

            // Persist chunks to database
            for (DocumentChunk chunk : chunks) {
                documentChunkMapper.insert(chunk);
            }

            // Insert into Milvus
            if (milvusService.isAvailable() && embeddings.size() == chunks.size()
                    && embeddings.stream().allMatch(vector -> vector != null && !vector.isEmpty())) {
                try {
                    milvusService.insertVectors(milvusIds, documentIds, chunkIndices, chunkContents, embeddings);
                } catch (Exception e) {
                    log.warn("Milvus indexing failed for document [{}], dense retrieval will be unavailable: {}",
                            id, e.getMessage());
                }
            } else {
                log.info("Skipping Milvus indexing for document [{}]", id);
            }

            // Index into BM25 (Elasticsearch)
            try {
                for (int i = 0; i < chunks.size(); i++) {
                    List<String> tags = document.getTags() != null
                            ? List.of(document.getTags().split(",")) : List.of();
                    bm25Service.indexDocument(
                            milvusIds.get(i), documentIdStr, i,
                            document.getTitle(), chunks.get(i).getContent(), tags);
                }
            } catch (Exception e) {
                log.warn("BM25 indexing failed for document [{}], sparse retrieval will be unavailable: {}",
                        id, e.getMessage());
            }

            // Update document status
            document.setStatus(DocumentStatus.COMPLETED.getCode());
            document.setChunkCount(chunks.size());
            document.setUpdatedAt(LocalDateTime.now());
            documentMapper.updateById(document);

            log.info("Document [{}] processing completed: {} chunks indexed", id, chunks.size());

        } catch (Exception e) {
            log.error("Document [{}] processing failed: {}", id, e.getMessage(), e);
            document.setStatus(DocumentStatus.FAILED.getCode());
            document.setUpdatedAt(LocalDateTime.now());
            documentMapper.updateById(document);
        }
    }

    // ----------------------------------------------------------------
    //  Internal Methods
    // ----------------------------------------------------------------

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
     * 使用 Apache Tika 解析文档，支持 PDF、Word、HTML 等多种格式。
     *
     * @param filePath 文件路径
     * @return 提取的文本内容
     */
    private String parseWithTika(Path filePath) {
        try (InputStream inputStream = Files.newInputStream(filePath)) {
            String content = tika.parseToString(inputStream);
            log.info("Tika parsed file [{}], extracted {} characters", filePath.getFileName(), content.length());
            return content;
        } catch (IOException | TikaException e) {
            log.error("Tika failed to parse file [{}]: {}", filePath, e.getMessage(), e);
            throw new RuntimeException("文档解析失败: " + e.getMessage(), e);
        }
    }
}
