package com.agi.assistant.service.impl;

import com.agi.assistant.config.OpenAIConfig;
import com.agi.assistant.mapper.ChatMessageMapper;
import com.agi.assistant.mapper.ChatSessionMapper;
import com.agi.assistant.model.dto.ChatRequest;
import com.agi.assistant.model.dto.ChatStreamEvent;
import com.agi.assistant.model.dto.SandboxExecuteRequest;
import com.agi.assistant.model.dto.SandboxExecuteResponse;
import com.agi.assistant.model.entity.ChatMessage;
import com.agi.assistant.model.entity.ChatSession;
import com.agi.assistant.model.entity.SearchResult;
import com.agi.assistant.service.ChatService;
import com.agi.assistant.service.SandboxService;
import com.agi.assistant.service.memory.ContextAssembly;
import com.agi.assistant.service.rag.HybridRetrievalService;
import com.agi.assistant.service.rag.WebSearchService;
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

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@Lazy
public class ChatServiceImpl implements ChatService {

    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile(
            "```(python|javascript|java|py|js)\\s*\\n([\\s\\S]*?)```",
            Pattern.CASE_INSENSITIVE);

    private static final List<String> SEARCH_KEYWORDS = List.of(
            "搜索", "查找", "最新", "新闻", "search", "latest", "news",
            "今天", "今日", "最近", "现在", "目前", "什么是", "谁是",
            "天气", "股价", "汇率", "实时"
    );

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final HybridRetrievalService hybridRetrievalService;
    private final ContextAssembly contextAssembly;
    private final OpenAIConfig openAIConfig;
    private final ObjectMapper objectMapper;
    private final WebClient streamingWebClient;
    private final WebSearchService webSearchService;
    private final SandboxService sandboxService;

    public ChatServiceImpl(ChatSessionMapper chatSessionMapper,
                           ChatMessageMapper chatMessageMapper,
                           HybridRetrievalService hybridRetrievalService,
                           ContextAssembly contextAssembly,
                           OpenAIConfig openAIConfig,
                           ObjectMapper objectMapper,
                           @Qualifier("streamingWebClient") WebClient streamingWebClient,
                           WebSearchService webSearchService,
                           SandboxService sandboxService) {
        this.chatSessionMapper = chatSessionMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.hybridRetrievalService = hybridRetrievalService;
        this.contextAssembly = contextAssembly;
        this.openAIConfig = openAIConfig;
        this.objectMapper = objectMapper;
        this.streamingWebClient = streamingWebClient;
        this.webSearchService = webSearchService;
        this.sandboxService = sandboxService;
    }

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

            // 4. Web search (if message contains search keywords)
            List<SearchResult> webResults = new ArrayList<>();
            if (needsWebSearch(request.getMessage())) {
                try {
                    webResults = webSearchService.search(request.getMessage(), 5);
                    if (!webResults.isEmpty()) {
                        emitter.send(SseEmitter.event()
                                .name("websearch")
                                .data(objectMapper.writeValueAsString(
                                        ChatStreamEvent.source(webResults))));
                        log.info("Web search returned {} results for query", webResults.size());
                    }
                } catch (Exception e) {
                    log.warn("Web search failed: {}", e.getMessage());
                }
            }

            // 5. Build context-enriched prompt
            Map<String, Object> context = new HashMap<>();
            if (request.isUseMemory()) {
                try {
                    context = contextAssembly.assembleContext(userId, request.getMessage());
                } catch (Exception e) {
                    log.warn("Memory assembly failed: {}", e.getMessage());
                }
            }

            // Merge all search results for system prompt
            List<SearchResult> allResults = new ArrayList<>(searchResults);
            allResults.addAll(webResults);

            String systemPrompt = buildSystemPrompt(context, allResults);
            List<Map<String, String>> messages = buildMessages(systemPrompt, sessionId, request.getMessage());

            // 6. Stream response from LLM
            AtomicReference<StringBuilder> fullResponse = new AtomicReference<>(new StringBuilder());
            java.util.concurrent.atomic.AtomicBoolean emitterCompleted = new java.util.concurrent.atomic.AtomicBoolean(false);

            Map<String, Object> requestBody = Map.of(
                    "model", openAIConfig.getModel(),
                    "messages", messages,
                    "temperature", openAIConfig.getTemperature(),
                    "max_tokens", openAIConfig.getMaxTokens(),
                    "stream", true
            );

            final StringBuilder lineBuffer = new StringBuilder();

            streamingWebClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToFlux(DataBuffer.class)
                    .map(buf -> {
                        String s = buf.toString(StandardCharsets.UTF_8);
                        DataBufferUtils.release(buf);
                        return s;
                    })
                    .doOnNext(chunk -> {
                        try {
                            lineBuffer.append(chunk);
                            int nlIdx;
                            while ((nlIdx = lineBuffer.indexOf("\n")) >= 0) {
                                String line = lineBuffer.substring(0, nlIdx).trim();
                                lineBuffer.delete(0, nlIdx + 1);
                                if (line.isEmpty()) continue;
                                processSseLine(line, emitter, fullResponse);
                            }
                        } catch (Exception e) {
                            log.debug("Error processing stream chunk: {}", e.getMessage());
                        }
                    })
                    .doOnError(e -> {
                        log.error("Stream error: {}", e.getMessage(), e);
                        if (emitterCompleted.compareAndSet(false, true)) {
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("error")
                                        .data("Stream error: " + e.getMessage()));
                            } catch (IOException ignored) {
                            }
                            emitter.completeWithError(e);
                        }
                    })
                    .doOnComplete(() -> {
                        if (lineBuffer.length() > 0) {
                            try {
                                processSseLine(lineBuffer.toString().trim(), emitter, fullResponse);
                            } catch (Exception ignored) {}
                        }
                        if (emitterCompleted.compareAndSet(false, true)) {
                            try {
                                String assistantContent = fullResponse.get().toString();

                                // 7. Detect and execute code blocks in sandbox
                                if (!assistantContent.isEmpty()) {
                                    executeCodeBlocks(assistantContent, emitter);
                                }

                                // 8. Save assistant message
                                if (!assistantContent.isEmpty()) {
                                    ChatMessage assistantMessage = new ChatMessage();
                                    assistantMessage.setSessionId(sessionId);
                                    assistantMessage.setRole("assistant");
                                    assistantMessage.setContent(assistantContent);
                                    assistantMessage.setCreatedAt(LocalDateTime.now());
                                    chatMessageMapper.insert(assistantMessage);
                                }
                                emitter.complete();
                            } catch (Exception e) {
                                log.error("Error completing stream: {}", e.getMessage(), e);
                                try { emitter.complete(); } catch (Exception ignored) {}
                            }
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
        ChatSession session = chatSessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new RuntimeException("Session not found or access denied");
        }
        chatMessageMapper.delete(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId));
        chatSessionMapper.deleteById(sessionId);
        log.info("Deleted session [{}] for user [{}]", sessionId, userId);
    }

    @Override
    public List<ChatMessage> getSessionMessages(Long sessionId, Long userId) {
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

    private void processSseLine(String line, SseEmitter emitter,
                                AtomicReference<StringBuilder> fullResponse) throws Exception {
        if (line == null || line.isEmpty()) return;

        String data;
        if (line.startsWith("data: ")) {
            data = line.substring(6);
        } else if (line.startsWith("data:")) {
            data = line.substring(5);
        } else {
            return;
        }

        if (data.equals("[DONE]")) return;
        if (!data.startsWith("{")) return;

        JsonNode root = objectMapper.readTree(data);
        JsonNode choices = root.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            JsonNode delta = choices.get(0).path("delta");
            String content = extractTextContent(delta.path("content"));
            if (content != null) {
                fullResponse.get().append(content);
                emitter.send(SseEmitter.event()
                        .name("content")
                        .data(objectMapper.writeValueAsString(
                                ChatStreamEvent.content(content))));
            }
        }
    }

    /**
     * Check if the user message likely needs web search.
     */
    private boolean needsWebSearch(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();
        for (String keyword : SEARCH_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Detect code blocks in the assistant's response and execute them in sandbox.
     */
    private void executeCodeBlocks(String content, SseEmitter emitter) {
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(content);
        while (matcher.find()) {
            String lang = matcher.group(1).toLowerCase();
            String code = matcher.group(2).trim();
            if (code.isEmpty()) continue;

            // Normalize language name
            String language = switch (lang) {
                case "py" -> "python";
                case "js" -> "javascript";
                default -> lang;
            };

            log.info("Detected code block, language={}, length={}", language, code.length());
            try {
                SandboxExecuteRequest req = new SandboxExecuteRequest();
                req.setLanguage(language);
                req.setCode(code);
                req.setTimeout(30);

                SandboxExecuteResponse response = sandboxService.execute(req);

                // Send sandbox result as SSE event
                Map<String, Object> sandboxEvent = new HashMap<>();
                sandboxEvent.put("language", language);
                sandboxEvent.put("code", code);
                sandboxEvent.put("output", response.getOutput());
                sandboxEvent.put("error", response.getError());
                sandboxEvent.put("exitCode", response.getExitCode());
                sandboxEvent.put("executionTime", response.getExecutionTime());

                emitter.send(SseEmitter.event()
                        .name("sandbox")
                        .data(objectMapper.writeValueAsString(sandboxEvent)));

                log.info("Sandbox execution completed: exitCode={}, time={}ms",
                        response.getExitCode(), response.getExecutionTime());
            } catch (Exception e) {
                log.warn("Sandbox execution failed: {}", e.getMessage());
                try {
                    Map<String, Object> errorEvent = new HashMap<>();
                    errorEvent.put("language", language);
                    errorEvent.put("code", code);
                    errorEvent.put("error", "Execution failed: " + e.getMessage());
                    errorEvent.put("exitCode", -1);
                    emitter.send(SseEmitter.event()
                            .name("sandbox")
                            .data(objectMapper.writeValueAsString(errorEvent)));
                } catch (Exception ignored) {}
            }
        }
    }

    private String buildSystemPrompt(Map<String, Object> context,
                                     List<SearchResult> searchResults) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个智能学习助手。基于提供的上下文和你的知识回答用户的问题。\n\n");
        prompt.append("如果用户要求你写代码，请直接写出完整的代码。");
        prompt.append("代码会被自动在沙箱环境中执行并返回结果。\n\n");

        if (!searchResults.isEmpty()) {
            prompt.append("## 参考资料\n");
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

        if (context.containsKey("longTermRecall")) {
            @SuppressWarnings("unchecked")
            List<String> recall = (List<String>) context.get("longTermRecall");
            if (recall != null && !recall.isEmpty()) {
                prompt.append("## 相关记忆\n");
                for (int i = 0; i < recall.size(); i++) {
                    prompt.append(i + 1).append(". ").append(recall.get(i)).append("\n");
                }
                prompt.append("\n");
            }
        }

        return prompt.toString();
    }

    private List<Map<String, String>> buildMessages(String systemPrompt, Long sessionId,
                                                     String currentMessage) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));

        List<ChatMessage> history = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByDesc(ChatMessage::getCreatedAt)
                        .last("LIMIT 20"));

        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage msg = history.get(i);
            messages.add(Map.of("role", msg.getRole(), "content", msg.getContent()));
        }

        return messages;
    }

    private String extractTextContent(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            return null;
        }
        String text = node.asText();
        return (text != null && !text.isEmpty()) ? text : null;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "New Chat";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
