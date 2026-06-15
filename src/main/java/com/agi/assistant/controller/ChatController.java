package com.agi.assistant.controller;

import com.agi.assistant.model.dto.ChatRequest;
import com.agi.assistant.model.entity.ChatMessage;
import com.agi.assistant.model.entity.ChatSession;
import com.agi.assistant.model.vo.Result;
import com.agi.assistant.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@Tag(name = "Chat", description = "聊天接口")
public class ChatController {

    private final ChatService chatService;

    /**
     * Alias for /stream — some frontends POST to /api/chat directly
     */
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式聊天(别名)", description = "POST /api/chat 的别名，兼容前端直接调用")
    public SseEmitter chat(
            @Valid @RequestBody ChatRequest request,
            @Parameter(description = "用户ID") @RequestHeader(value = "X-User-Id", required = false, defaultValue = "1") Long userId) {
        log.info("Chat request (alias) from user {}, session {}", userId, request.getSessionId());
        SseEmitter emitter = new SseEmitter(120_000L);
        chatService.streamChat(request, userId, emitter);
        return emitter;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式聊天", description = "通过SSE进行流式对话")
    public SseEmitter streamChat(
            @Valid @RequestBody ChatRequest request,
            @Parameter(description = "用户ID") @RequestHeader(value = "X-User-Id", required = false, defaultValue = "1") Long userId) {
        log.info("Stream chat request from user {}, session {}", userId, request.getSessionId());
        SseEmitter emitter = new SseEmitter(120_000L);
        chatService.streamChat(request, userId, emitter);
        return emitter;
    }

    @GetMapping("/sessions")
    @Operation(summary = "获取会话列表", description = "获取当前用户的所有会话")
    public Result<List<ChatSession>> listSessions(
            @Parameter(description = "用户ID") @RequestHeader(value = "X-User-Id", required = false, defaultValue = "1") Long userId) {
        log.info("List sessions for user {}", userId);
        List<ChatSession> sessions = chatService.listSessions(userId);
        return Result.ok(sessions);
    }

    @PostMapping("/sessions")
    @Operation(summary = "创建会话", description = "创建一个新的聊天会话")
    public Result<ChatSession> createSession(
            @Parameter(description = "用户ID") @RequestHeader(value = "X-User-Id", required = false, defaultValue = "1") Long userId,
            @Parameter(description = "会话标题") @RequestParam(required = false) String title) {
        log.info("Create session for user {}, title {}", userId, title);
        ChatSession session = chatService.createSession(userId, title);
        return Result.ok(session);
    }

    @DeleteMapping("/sessions/{id}")
    @Operation(summary = "删除会话", description = "删除指定的聊天会话")
    public Result<Void> deleteSession(
            @Parameter(description = "会话ID") @PathVariable("id") Long sessionId,
            @Parameter(description = "用户ID") @RequestHeader(value = "X-User-Id", required = false, defaultValue = "1") Long userId) {
        log.info("Delete session {} for user {}", sessionId, userId);
        chatService.deleteSession(sessionId, userId);
        return Result.ok();
    }

    @GetMapping("/sessions/{id}/messages")
    @Operation(summary = "获取会话消息", description = "获取指定会话的所有消息")
    public Result<List<ChatMessage>> getSessionMessages(
            @Parameter(description = "会话ID") @PathVariable("id") Long sessionId,
            @Parameter(description = "用户ID") @RequestHeader(value = "X-User-Id", required = false, defaultValue = "1") Long userId) {
        log.info("Get messages for session {}, user {}", sessionId, userId);
        List<ChatMessage> messages = chatService.getSessionMessages(sessionId, userId);
        return Result.ok(messages);
    }
}
