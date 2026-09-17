package com.agi.assistant.controller;

import com.agi.assistant.model.entity.Document;
import com.agi.assistant.model.vo.Result;
import com.agi.assistant.service.DocumentService;
import com.agi.assistant.service.security.AccessDeniedException;
import com.agi.assistant.service.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DocumentController#getDocument} 的归属校验（IDOR）离线单测。
 * <p>
 * 旧实现只按 id 查询、不比对归属 —— 改个 id 就能读到别人的文档。
 *
 * @author Alex
 */
class DocumentControllerOwnershipTest {

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @DisplayName("非归属用户读取他人文档 → 拒绝；归属用户 → 正常返回")
    void getDocumentEnforcesOwnership() {
        DocumentService documentService = mock(DocumentService.class);
        DocumentController controller = new DocumentController(documentService);

        Document doc = new Document();
        doc.setId(1L);
        doc.setUserId(1L);
        when(documentService.getDocument(1L)).thenReturn(doc);

        // 当前登录用户 = 2，访问归属用户 1 的文档 → 拒绝
        UserContext.setUserId(2L);
        assertThatThrownBy(() -> controller.getDocument("1"))
                .isInstanceOf(AccessDeniedException.class);

        // 当前登录用户 = 1（归属者）→ 正常
        UserContext.setUserId(1L);
        Result<Document> result = controller.getDocument("1");
        assertThat(result.getData().getId()).isEqualTo(1L);
    }
}
