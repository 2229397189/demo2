package com.agi.assistant.controller;

import com.agi.assistant.model.dto.DocumentUploadRequest;
import com.agi.assistant.model.entity.Document;
import com.agi.assistant.model.vo.PageResult;
import com.agi.assistant.model.vo.Result;
import com.agi.assistant.service.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
@Tag(name = "Document", description = "文档管理接口")
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传文档", description = "上传文档文件及元数据")
    public Result<Document> uploadDocument(
            @Parameter(description = "文档文件") @RequestParam("file") MultipartFile file,
            @Parameter(description = "文档标题") @RequestParam(required = false) String title,
            @Parameter(description = "标签") @RequestParam(required = false) String tags,
            @Parameter(description = "来源") @RequestParam(required = false) String source,
            @Parameter(description = "用户ID") @RequestHeader(value = "X-User-Id", required = false, defaultValue = "1") Long userId) {
        log.info("Upload document by user {}, fileName {}", userId, file.getOriginalFilename());
        DocumentUploadRequest request = new DocumentUploadRequest();
        request.setTitle(title);
        request.setTags(tags);
        request.setSource(source);
        Document document = documentService.uploadDocument(file, request, userId);
        return Result.ok(document);
    }

    @GetMapping
    @Operation(summary = "文档列表", description = "分页获取用户的文档列表")
    public Result<PageResult<Document>> listDocuments(
            @Parameter(description = "用户ID") @RequestHeader(value = "X-User-Id", required = false, defaultValue = "1") Long userId,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "10") int size) {
        log.info("List documents for user {}, page {}, size {}", userId, page, size);
        PageResult<Document> result = documentService.listDocuments(userId, page, size);
        return Result.ok(result);
    }

    @GetMapping("/{id}")
    @Operation(summary = "文档详情", description = "获取指定文档的详细信息")
    public Result<Document> getDocument(
            @Parameter(description = "文档ID") @PathVariable("id") String documentId) {
        Long id = parseId(documentId);
        if (id == null) {
            return Result.fail(400, "Invalid document ID: " + documentId);
        }
        log.info("Get document {}", id);
        Document document = documentService.getDocument(id);
        return Result.ok(document);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除文档", description = "删除指定的文档")
    public Result<Void> deleteDocument(
            @Parameter(description = "文档ID") @PathVariable("id") String documentId,
            @Parameter(description = "用户ID") @RequestHeader(value = "X-User-Id", required = false, defaultValue = "1") Long userId) {
        Long id = parseId(documentId);
        if (id == null) {
            log.warn("Invalid document ID in delete request: {}", documentId);
            return Result.fail(400, "Invalid document ID: " + documentId);
        }
        log.info("Delete document {} by user {}", id, userId);
        documentService.deleteDocument(id, userId);
        return Result.ok();
    }

    @PostMapping("/{id}/process")
    @Operation(summary = "处理文档", description = "触发文档的解析、分块、向量化和索引流程")
    public Result<Void> processDocument(
            @Parameter(description = "文档ID") @PathVariable("id") String documentId) {
        Long id = parseId(documentId);
        if (id == null) {
            return Result.fail(400, "Invalid document ID: " + documentId);
        }
        log.info("Process document {}", id);
        documentService.processDocument(id);
        return Result.ok();
    }

    private Long parseId(String idStr) {
        if (idStr == null || idStr.isBlank() || "undefined".equals(idStr) || "null".equals(idStr)) {
            return null;
        }
        try {
            return Long.parseLong(idStr);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
