package com.agi.assistant.service.impl;

import com.agi.assistant.config.OpenAIConfig;
import com.agi.assistant.mapper.ChatMessageMapper;
import com.agi.assistant.mapper.ChatSessionMapper;
import com.agi.assistant.model.dto.ChatRequest;
import com.agi.assistant.model.dto.ChatStreamEvent;
import com.agi.assistant.model.entity.ChatMessage;
import com.agi.assistant.model.entity.ChatSession;
import com.agi.assistant.model.entity.SearchResult;
import com.agi.assistant.service.ChatService;
import com.agi.assistant.service.memory.ContextAssembly;
import com.agi.assistant.service.rag.HybridRetrievalService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ChatService implementation.
 * <p>
 * Provides streaming chat via SSE, backed by OpenAI-compatible API,
 * with RAG retrieval and memory context assembly.
 */
@Slf4j
@Service
@Lazy
public class ChatServiceImpl implements ChatService {

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final HybridRetrievalService hybridRetrievalService;
    private final ContextAssembly contextAssembly;
    private final OpenAIConfig openAIConfig;
    private final ObjectMapper objectMapper;
    private final WebClient streamingWebClient;

    public ChatServiceImpl(ChatSessionMapper chatSessionMapper,
                           ChatMessageMapper chatMessageMapper,
                           HybridRetrievalService hybridRetrievalService,
                           ContextAssembly contextAssembly,
                           OpenAIConfig openAIConfig,
                           ObjectMapper objectMapper,
                           @Qualifier("streamingWebClient") WebClient streamingWebClient) {
        this.chatSessionMapper = chatSessionMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.hybridRetrievalService = hybridRetrievalService;
        this.contextAssembly = contextAssembly;
        this.openAIConfig = openAIConfig;
        this.objectMapper = objectMapper;
        this.streamingWebClient = streamingWebClient;
    }

    // ----------------------------------------------------------------
    //  Streaming Chat
    // ----------------------------------------------------------------

    @Override
    public void streamChat(ChatRequest request, Long userId, SseEmitter emitter) {
        log.info("Stream chat: userId={}, sessionId={}, message length={}",
                userId, request.getSessionId(),
                request.getMessage() != null ? request.getMessage().length() : 0);

        try {
            // 1. Ensure session exists
            Long sid = request.getSessionId();
            if (sid == null) {
                ChatSession session = createSession(userId, truncate(request.getMessage(), 50));
                sid = session.getId();
            }
            final Long sessionId = sid;

            // 2. Save user message
            ChatMessage userMessage = new ChatMessage();
            userMessage.setSessionId(sessionId);
            userMessage.setRole("user");
            userMessage.setContent(request.getMessage());
            userMessage.setCreatedAt(LocalDateTime.now());
            chatMessageMapper.insert(userMessage);

            // 3. RAG retrieval
            List<SearchResult> searchResults = new ArrayList<>();
            String strategy = request.getRetrievalStrategy() != null
                    ? request.getRetrievalStrategy() : "HYBRID";
            try {
                searchResults = hybridRetrievalService.retrieve(
                        request.getMessage(), strategy, 5);
                if (!searchResults.isEmpty()) {
                    emitter.send(SseEmitter.event()
                            .name("source")
                            .data(objectMapper.writeValueAsString(
                                    ChatStreamEvent.source(searchResults))));
                }
            } catch (Exception e) {
                log.warn("RAG retrieval failed, continuing without context: {}", e.getMessage());
            }

            // 4. Build context-enriched prompt
            Map<String, Object> context = new HashMap<>();
            if (request.isUseMemory()) {
                try {
                    context = contextAssembly.assembleContext(userId, request.getMessage());
                } catch (Exception e) {
                    log.warn("Memory assembly failed: {}", e.getMessage());
                }
            }

            String systemPrompt = buildSystemPrompt(context, searchResults);
            List<Map<String, String>> messages = buildMessages(systemPrompt, sessionId, request.getMessage());

            // 5. Stream response from LLM
            AtomicReference<StringBuilder> fullResponse = new AtomicReference<>(new StringBuilder());

            Map<String, Object> requestBody = Map.of(
                    "model", openAIConfig.getModel(),
                    "messages", messages,
                    "temperature", openAIConfig.getTemperature(),
                    "max_tokens", openAIConfig.getMaxTokens(),
                    "stream", true
            );

            streamingWebClient.post()
                    .uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToFlux(String.class)
                    .doOnNext(chunk -> {
                        try {
                            processStreamChunk(chunk, emitter, fullResponse);
                        } catch (Exception e) {
                            log.debug("Error processing stream chunk: {}", e.getMessage());
                        }
                    })
                    .doOnError(e -> {
                        log.error("Stream error: {}", e.getMessage(), e);
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("error")
                                    .data("Stream error: " + e.getMessage()));
                        } catch (IOException ignored) {
                        }
                        emitter.completeWithError(e);
                    })
                    .doOnComplete(() -> {
                        try {
                            // Save assistant message
                            String assistantContent = fullResponse.get().toString();
                            if (!assistantContent.isEmpty()) {
                                ChatMessage assistantMessage = new ChatMessage();
                                assistantMessage.setSessionId(sessionId);
                                assistantMessage.setRole("assistant");
                                assistantMessage.setContent(assistantContent);
                                assistantMessage.setCreatedAt(LocalDateTime.now());
                                chatMessageMapper.insert(assistantMessage);
                            }

                            // Send [DONE] marker (OpenAI SSE convention)
                            emitter.send("data: [DONE]\n\n");
                            emitter.complete();
                        } catch (Exception e) {
                            log.error("Error completing stream: {}", e.getMessage(), e);
                            emitter.complete();
                        }
                    })
                    .subscribe();

        } catch (Exception e) {
            log.error("Stream chat failed: {}", e.getMessage(), e);
            try {
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data("Chat error: " + e.getMessage()));
            } catch (IOException ignored) {
            }
            emitter.completeWithError(e);
        }
    }

    // ----------------------------------------------------------------
    //  Session CRUD
    // ----------------------------------------------------------------

    @Override
    public List<ChatSession> listSessions(Long userId) {
        return chatSessionMapper.selectList(
                new LambdaQueryWrapper<ChatSession>()
                        .eq(ChatSession::getUserId, userId)
                        .orderByDesc(ChatSession::getUpdatedAt));
    }

    @Override
    public ChatSession createSession(Long userId, String title) {
        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setTitle(title != null ? title : "New Chat");
        session.setRetrievalStrategy("HYBRID");
        session.setStatus(1);
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        chatSessionMapper.insert(session);
        log.info("Created session [{}] for user [{}]", session.getId(), userId);
        return session;
    }

    @Override
    public void deleteSession(Long sessionId, Long userId) {
        // Verify ownership
        ChatSession session = chatSessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new RuntimeException("Session not found or access denied");
        }

        // Delete messages
        chatMessageMapper.delete(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId));

        // Delete session
        chatSessionMapper.deleteById(sessionId);
        log.info("Deleted session [{}] for user [{}]", sessionId, userId);
    }

    @Override
    public List<ChatMessage> getSessionMessages(Long sessionId, Long userId) {
        // Verify ownership
        ChatSession session = chatSessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new RuntimeException("Session not found or access denied");
        }

        return chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByAsc(ChatMessage::getCreatedAt));
    }

    // ----------------------------------------------------------------
    //  Internal Methods
    // ----------------------------------------------------------------

    /**
     * Process a single SSE chunk from the OpenAI streaming API.
     */
    private void processStreamChunk(String chunk, SseEmitter emitter,
                                    AtomicReference<StringBuilder> fullResponse) throws Exception {
        if (chunk == null || chunk.isBlank() || chunk.equals("[DONE]")) {
            return;
        }

        // Handle SSE format: lines starting with "data: "
        for (String line : chunk.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || !line.startsWith("data: ")) {
                continue;
            }

            String data = line.substring(6).trim();
            if (data.equals("[DONE]")) {
                return;
            }

            try {
                JsonNode root = objectMapper.readTree(data);
                JsonNode choices = root.path("choices");
                if (choices.isArray() && choices.size() > 0) {
                    JsonNode delta = choices.get(0).path("delta");
                    JsonNode contentNode = delta.path("content");
                    if (!contentNode.isMissingNode() && contentNode.asText() != null) {
                        String content = contentNode.asText();
                        fullResponse.get().append(content);
                        emitter.send(SseEmitter.event()
                                .name("content")
                                .data(objectMapper.writeValueAsString(
                                        ChatStreamEvent.content(content))));
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to parse chunk: {}", e.getMessage());
            }
        }
    }

    /**
     * Build system prompt with RAG context and memory.
     */
    private String buildSystemPrompt(Map<String, Object> context,
                                     List<SearchResult> searchResults) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are an intelligent learning assistant. ");
        prompt.append("Answer the user's questions based on the provided context and your knowledge.\n\n");

        // Add RAG context
        if (!searchResults.isEmpty()) {
            prompt.append("## Reference Materials\n");
            for (int i = 0; i < searchResults.size(); i++) {
                SearchResult r = searchResults.get(i);
                prompt.append("[").append(i + 1).append("] ");
                if (r.getTitle() != null) {
                    prompt.append(r.getTitle()).append(": ");
                }
                prompt.append(r.getContent()).append("\n");
            }
            prompt.append("\n");
        }

        // Add memory context
        if (context.containsKey("longTermRecall")) {
            @SuppressWarnings("unchecked")
            List<String> recall = (List<String>) context.get("longTermRecall");
            if (recall != null && !recall.isEmpty()) {
                prompt.append("## Relevant Memories\n");
                for (int i = 0; i < recall.size(); i++) {
                    prompt.append(i + 1).append(". ").append(recall.get(i)).append("\n");
                }
                prompt.append("\n");
            }
        }

        return prompt.toString();
    }

    /**
     * Build the messages list for the LLM API call.
     */
    private List<Map<String, String>> buildMessages(String systemPrompt, Long sessionId,
                                                     String currentMessage) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));

        // Load recent history
        List<ChatMessage> history = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByDesc(ChatMessage::getCreatedAt)
                        .last("LIMIT 20"));

        // Reverse to chronological order
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage msg = history.get(i);
            messages.add(Map.of("role", msg.getRole(), "content", msg.getContent()));
        }

        return messages;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "New Chat";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
