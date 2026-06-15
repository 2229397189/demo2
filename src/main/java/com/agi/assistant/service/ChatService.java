package com.agi.assistant.service;

import com.agi.assistant.model.dto.ChatRequest;
import com.agi.assistant.model.dto.ChatStreamEvent;
import com.agi.assistant.model.entity.ChatMessage;
import com.agi.assistant.model.entity.ChatSession;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

public interface ChatService {

    /**
     * 流式聊天，通过SSE推送事件
     */
    void streamChat(ChatRequest request, Long userId, SseEmitter emitter);

    /**
     * 获取用户的会话列表
     */
    List<ChatSession> listSessions(Long userId);

    /**
     * 创建新会话
     */
    ChatSession createSession(Long userId, String title);

    /**
     * 删除会话
     */
    void deleteSession(Long sessionId, Long userId);

    /**
     * 获取会话的历史消息
     */
    List<ChatMessage> getSessionMessages(Long sessionId, Long userId);
}
