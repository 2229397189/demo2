package com.agi.assistant.controller;

import com.agi.assistant.model.dto.ChatRequest;
import com.agi.assistant.model.entity.ChatMessage;
import com.agi.assistant.model.entity.ChatSession;
import com.agi.assistant.model.vo.Result;
import com.agi.assistant.service.ChatService;
import com.agi.assistant.service.security.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 聊天接口。
 * <p>
 * 权限修复说明：此前用户身份来自 {@code @RequestHeader X-User-Id}（还带 defaultValue="1"），
 * 任何人改一下请求头就能读写他人会话。现在身份统一从
 * {@link UserContext}（由认证拦截器校验 JWT 后写入）获取。
 */
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
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    @Operation(summary = "流式聊天(别名)", description = "POST /api/chat 的别名，兼容前端直接调用")
    public SseEmitter chat(@Valid @RequestBody ChatRequest request) {
        Long userId = UserContext.requireUserId();
        log.info("Chat request (alias) from user {}, session {}", userId, request.getSessionId());
        SseEmitter emitter = new SseEmitter(300_000L); // 5 min - must exceed WebClient timeout
        chatService.streamChat(request, userId, emitter);
        return emitter;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    @Operation(summary = "流式聊天", description = "通过SSE进行流式对话")
    public SseEmitter streamChat(@Valid @RequestBody ChatRequest request) {
        Long userId = UserContext.requireUserId();
        log.info("Stream chat request from user {}, session {}", userId, request.getSessionId());
        SseEmitter emitter = new SseEmitter(300_000L); // 5 min - must exceed WebClient timeout
        chatService.streamChat(request, userId, emitter);
        return emitter;
    }

    @GetMapping("/sessions")
    @Operation(summary = "获取会话列表", description = "获取当前用户的所有会话")
    public Result<List<ChatSession>> listSessions() {
        Long userId = UserContext.requireUserId();
        log.info("List sessions for user {}", userId);
        return Result.ok(chatService.listSessions(userId));
    }

    @PostMapping("/sessions")
    @Operation(summary = "创建会话", description = "创建一个新的聊天会话")
    public Result<ChatSession> createSession(@RequestParam(required = false) String title) {
        Long userId = UserContext.requireUserId();
        log.info("Create session for user {}, title {}", userId, title);
        return Result.ok(chatService.createSession(userId, title));
    }

    @DeleteMapping("/sessions/{id}")
    @Operation(summary = "删除会话", description = "删除指定的聊天会话")
    public Result<Void> deleteSession(@PathVariable("id") Long sessionId) {
        Long userId = UserContext.requireUserId();
        log.info("Delete session {} for user {}", sessionId, userId);
        chatService.deleteSession(sessionId, userId);
        return Result.ok();
    }

    @GetMapping("/sessions/{id}/messages")
    @Operation(summary = "获取会话消息", description = "获取指定会话的所有消息")
    public Result<List<ChatMessage>> getSessionMessages(@PathVariable("id") Long sessionId) {
        Long userId = UserContext.requireUserId();
        log.info("Get messages for session {}, user {}", sessionId, userId);
        return Result.ok(chatService.getSessionMessages(sessionId, userId));
    }
}
