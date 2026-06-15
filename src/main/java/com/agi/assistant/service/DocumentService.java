package com.agi.assistant.service;

import com.agi.assistant.model.dto.DocumentUploadRequest;
import com.agi.assistant.model.entity.Document;
import com.agi.assistant.model.vo.PageResult;
import org.springframework.web.multipart.MultipartFile;

public interface DocumentService {

    /**
     * 上传文档
     */
    Document uploadDocument(MultipartFile file, DocumentUploadRequest request, Long userId);

    /**
     * 分页查询文档列表
     */
    PageResult<Document> listDocuments(Long userId, int page, int size);

    /**
     * 获取文档详情
     */
    Document getDocument(Long id);

    /**
     * 删除文档
     */
    void deleteDocument(Long id, Long userId);

    /**
     * 触发文档处理流程（解析、分块、向量化、索引）
     */
    void processDocument(Long id);
}
