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
import com.agi.assistant.service.memory.MemoryConsolidation;
import com.agi.assistant.service.memory.ShortTermMemory;
import com.agi.assistant.service.rag.HybridRetrievalService;
import com.agi.assistant.service.rag.WebSearchService;
import com.agi.assistant.service.security.AuditService;
import com.agi.assistant.service.security.InputValidator;
import com.agi.assistant.model.enums.ToolRiskLevel;
import com.agi.assistant.service.agent.ReactEngine;
import com.agi.assistant.service.agent.RaceStrategy;
import com.agi.assistant.service.harness.HarnessRuntime;
import com.agi.assistant.service.harness.ChatFallbackProvider;
import com.agi.assistant.model.enums.RetrievalStrategy;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.Resource;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
    private final ShortTermMemory shortTermMemory;
    private final MemoryConsolidation memoryConsolidation;
    private final OpenAIConfig openAIConfig;
    private final ObjectMapper objectMapper;
    private final WebClient streamingWebClient;
    private final WebSearchService webSearchService;
    private final SandboxService sandboxService;
    private final InputValidator inputValidator;
    private final ReactEngine reactEngine;
    private final RaceStrategy raceStrategy;
    private final HarnessRuntime harnessRuntime;
    private final ChatFallbackProvider chatFallbackProvider;
    private final AuditService auditService;

    @Value("classpath:prompts/system-prompt.md")
    private Resource systemPromptResource;

    /** 缓存模板内容，避免每次请求都读文件 */
    private volatile String cachedSystemPromptTemplate;

    /**
     * 复杂问题判定阈值：消息长度超过此值时考虑使用 ReAct 模式。
     * <p>
     * 修复说明：此前是写死的常量 {@code COMPLEX_QUERY_THRESHOLD = 100}，
     * 而 application.yml 里的 {@code app.chat.react-min-length} 从来没有代码读过 ——
     * 改配置不生效，属于典型的「配置项是装饰品」。
     */
    @Value("${app.chat.react-min-length:300}")
    private int reactMinLength;

    /**
     * 是否自动把 LLM 回答里的代码块丢进沙箱执行。
     * <p>
     * 修复说明：{@code app.chat.auto-execute-code-blocks} 同样从未被消费，
     * 而代码执行是有资源成本和安全面的动作，必须由开关控制（默认关）。
     */
    @Value("${app.chat.auto-execute-code-blocks:false}")
    private boolean autoExecuteCodeBlocksR;

    /**
     * 是否用「关键词命中」触发 ReAct（默认关）。
     * <p>
     * 修复说明（P2-3）：下面的关键词表覆盖了「分析/对比/为什么/如何/explain…」
     * —— 几乎所有自然提问都会命中，加上「≥2 个问号」条件，ReAct 实际上
     * 对大多数问题都会触发：每次要多轮 LLM 调用、且跳过流式输出。
     * 现在长度阈值仍是主触发条件（可配），这两个激进条件改为显式开启才生效。
     */
    @Value("${app.chat.react-keywords-enabled:false}")
    private boolean reactKeywordsEnabled;

    /** 是否用「≥2 个问号」触发 ReAct（默认关） */
    @Value("${app.chat.react-multi-question-enabled:false}")
    private boolean reactMultiQuestionEnabled;

    /** 复杂问题关键词 */
    private static final List<String> COMPLEX_KEYWORDS = List.of(
            "分析", "对比", "比较", "总结", "归纳", "推理", "为什么",
            "怎么做", "如何", "步骤", "流程", "方案", "设计",
            "analyze", "compare", "summarize", "reason", "explain"
    );

    public ChatServiceImpl(ChatSessionMapper chatSessionMapper,
                           ChatMessageMapper chatMessageMapper,
                           HybridRetrievalService hybridRetrievalService,
                           ContextAssembly contextAssembly,
                           ShortTermMemory shortTermMemory,
                           MemoryConsolidation memoryConsolidation,
                           OpenAIConfig openAIConfig,
                           ObjectMapper objectMapper,
                           @Qualifier("streamingWebClient") WebClient streamingWebClient,
                           WebSearchService webSearchService,
                           SandboxService sandboxService,
                           InputValidator inputValidator,
                           ReactEngine reactEngine,
                           RaceStrategy raceStrategy,
                           HarnessRuntime harnessRuntime,
                           ChatFallbackProvider chatFallbackProvider,
                           @Lazy AuditService auditService) {
        this.chatSessionMapper = chatSessionMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.hybridRetrievalService = hybridRetrievalService;
        this.contextAssembly = contextAssembly;
        this.shortTermMemory = shortTermMemory;
        this.memoryConsolidation = memoryConsolidation;
        this.openAIConfig = openAIConfig;
        this.objectMapper = objectMapper;
        this.streamingWebClient = streamingWebClient;
        this.webSearchService = webSearchService;
        this.sandboxService = sandboxService;
        this.inputValidator = inputValidator;
        this.reactEngine = reactEngine;
        this.raceStrategy = raceStrategy;
        this.harnessRuntime = harnessRuntime;
        this.chatFallbackProvider = chatFallbackProvider;
        this.auditService = auditService;
    }

    @Override
    public void streamChat(ChatRequest request, Long userId, SseEmitter emitter) {
        log.info("Stream chat: userId={}, sessionId={}, message length={}",
                userId, request.getSessionId(),
                request.getMessage() != null ? request.getMessage().length() : 0);

        // 输入安全验证：只有高置信度攻击载荷才拦截；
        // 敏感词 / 「像 SQL」「像 XSS」这类弱信号放行但记审计，
        // 否则用户问「select 和 from 的执行顺序」都会被自己的助手拒答。
        if (request.getMessage() != null) {
            InputValidator.ValidationResult validation = inputValidator.validate(request.getMessage());
            if (validation.getSeverity() != InputValidator.Severity.CLEAN) {
                boolean blocked = validation.getSeverity() == InputValidator.Severity.BLOCK;
                auditInput(userId, validation, blocked);
                if (blocked) {
                    log.warn("Input validation BLOCKED for userId={}: {}", userId, validation.getBlockers());
                    try {
                        emitter.send(SseEmitter.event()
                                .name("error")
                                .data("输入内容包含高风险的注入载荷，已被安全策略拦截。"));
                    } catch (IOException ignored) {
                    }
                    emitter.complete();
                    return;
                }
            }
        }

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

            // Save to short-term memory
            shortTermMemory.addMessage(sessionId.toString(), userMessage);

            // 3. 并行检索：RaceStrategy 竞速 + HarnessRuntime 超时保障
            List<SearchResult> searchResults = new ArrayList<>();
            String strategy = request.getRetrievalStrategy() != null
                    ? request.getRetrievalStrategy() : "HYBRID";

            sendThinkingEvent(emitter, "retrieval", "正在多路竞速检索知识库...");

            // 使用 RaceStrategy 竞速多种检索策略
            //
            // 修复（并发状态机冲突 + SSE 击穿）：
            // 1) taskName 原为固定常量 "rag-retrieval"，而 HarnessRuntime 用它作 StateMachine
            //    的 key —— 多请求并发时共享同一 key、互相 reset / 推进状态，触发状态机非法
            //    转移。现改用请求级唯一的 taskKey(...) 隔离。
            // 2) 本处是四处 execute 调用里唯一没有 try 包裹的一处：一旦抛异常会直接击穿 SSE
            //    输出流，用户侧表现为对话突然中断。现在捕获后降级为空结果集，保证用户仍能
            //    拿到完整的 LLM 回答（只是缺少引用来源）。
            boolean retrievalDegraded = false;
            try {
                searchResults = harnessRuntime.execute(() -> {
                    if ("RACE".equalsIgnoreCase(strategy)) {
                        // 竞速模式：DENSE / SPARSE / GRAPH 三路竞赛，取最快返回
                        List<RetrievalStrategy> strategies = List.of(
                                com.agi.assistant.model.enums.RetrievalStrategy.DENSE,
                                com.agi.assistant.model.enums.RetrievalStrategy.SPARSE,
                                com.agi.assistant.model.enums.RetrievalStrategy.GRAPH
                        );
                        return raceStrategy.raceRetrieve(strategies, request.getMessage());
                    } else {
                        // 指定策略模式，带超时重试保障
                        return hybridRetrievalService.retrieve(request.getMessage(), strategy, 5);
                    }
                }, taskKey("rag-retrieval", String.valueOf(sessionId)), 5000,
                        // 真实降级：检索失败后先查进程内缓存，仍未命中则退化为空结果集，
                        // 绝不把「检索失败」升级成「整条对话失败」。
                        () -> asSearchResults(chatFallbackProvider.retrievalFallback(request.getMessage())));
            } catch (Exception e) {
                log.warn("RAG retrieval failed, degrading to empty result set: {}", e.getMessage());
                searchResults = new ArrayList<>();
                retrievalDegraded = true;
            }

            if (searchResults != null && !searchResults.isEmpty()) {
                Map<String, Object> sourceData = new HashMap<>();
                sourceData.put("type", "source");
                sourceData.put("content", searchResults);
                emitter.send(SseEmitter.event().name("source").data(sourceData));
                sendThinkingEvent(emitter, "retrieval_done",
                        "检索到 " + searchResults.size() + " 条相关内容");
            } else {
                searchResults = new ArrayList<>();
                sendThinkingEvent(emitter, "retrieval_done",
                        retrievalDegraded ? "检索已降级，继续生成回答" : "检索超时或无结果，继续生成回答");
            }

            // 4. Web search（条件触发 + HarnessRuntime 超时保障）
            List<SearchResult> webResults = new ArrayList<>();
            if (needsWebSearch(request.getMessage())) {
                sendThinkingEvent(emitter, "websearch", "正在搜索网络...");
                try {
                    webResults = harnessRuntime.execute(() ->
                                    webSearchService.search(request.getMessage(), 5),
                            taskKey("web-search", String.valueOf(sessionId)), 10000,
                            () -> asSearchResults(chatFallbackProvider.webSearchFallback(request.getMessage())));
                    if (webResults != null && !webResults.isEmpty()) {
                        Map<String, Object> webData = new HashMap<>();
                        webData.put("type", "websearch");
                        webData.put("content", webResults);
                        emitter.send(SseEmitter.event().name("websearch").data(webData));
                        sendThinkingEvent(emitter, "websearch_done",
                                "网络搜索完成，找到 " + webResults.size() + " 条结果");
                    }
                } catch (Exception e) {
                    log.warn("Web search failed or timed out: {}", e.getMessage());
                    webResults = new ArrayList<>();
                }
            }

            // 5. 记忆查询（HarnessRuntime 超时保障）
            Map<String, Object> context = new HashMap<>();
            if (request.isUseMemory()) {
                sendThinkingEvent(emitter, "memory", "正在查询记忆系统...");
                try {
                    context = harnessRuntime.execute(() ->
                                    contextAssembly.assembleContext(userId, sessionId.toString(),
                                            request.getMessage()),
                            taskKey("memory-assembly", String.valueOf(sessionId)), 5000,
                            () -> chatFallbackProvider.memoryFallback(userId, sessionId.toString(),
                                    request.getMessage()));
                    sendThinkingEvent(emitter, "memory_done", "记忆查询完成");
                } catch (Exception e) {
                    log.warn("Memory assembly failed or timed out: {}", e.getMessage());
                    context = new HashMap<>();
                }
            }

            // 6. 复杂问题检测 → ReAct 推理模式
            if (isComplexQuery(request.getMessage())) {
                sendThinkingEvent(emitter, "react", "检测到复杂问题，启动 ReAct 多步推理...");
                try {
                    final Long reactUserId = userId;
                    // 把 sessionId 一并交给 ReAct 引擎：运行态记忆（计划/工具调用/任务）
                    // 以 sessionId 为 key，没有它这层记忆就只能永远是空容器
                    String reactAnswer = harnessRuntime.execute(() ->
                                    reactEngine.run(request.getMessage(), 5, reactUserId,
                                            sessionId.toString()),
                            taskKey("react-engine", String.valueOf(sessionId)), 60000,
                            // 真正的降级：ReAct 失败时返回空串，走下面的普通流式回答，
                            // 而不是像以前那样只演戏式地返回 null
                            () -> chatFallbackProvider.reactFallback());

                    if (reactAnswer != null && !reactAnswer.isBlank()
                            && !reactAnswer.startsWith("经过多轮推理")) {
                        // ReAct 成功产出答案，直接使用
                        sendThinkingEvent(emitter, "react_done", "ReAct 推理完成");

                        // 发送 ReAct 答案
                        Map<String, Object> reactData = new HashMap<>();
                        reactData.put("type", "content");
                        reactData.put("content", reactAnswer);
                        emitter.send(SseEmitter.event().name("content").data(reactData));

                        // 保存消息
                        ChatMessage assistantMessage = new ChatMessage();
                        assistantMessage.setSessionId(sessionId);
                        assistantMessage.setRole("assistant");
                        assistantMessage.setContent(reactAnswer);
                        assistantMessage.setCreatedAt(LocalDateTime.now());
                        chatMessageMapper.insert(assistantMessage);
                        shortTermMemory.addMessage(sessionId.toString(), assistantMessage);

                        // 代码块检测与执行
                        executeCodeBlocks(reactAnswer, emitter);

                        // 异步记忆整合
                        CompletableFuture.runAsync(() -> {
                            try { memoryConsolidation.consolidate(userId, sessionId.toString()); } catch (Exception ignored) {}
                        });

                        sendThinkingEvent(emitter, "done", "回答完成");
                        emitter.complete();
                        return; // ReAct 模式完成，跳过后续 LLM 流式调用
                    }
                } catch (Exception e) {
                    log.warn("ReAct engine failed, falling back to normal mode: {}", e.getMessage());
                    sendThinkingEvent(emitter, "react_done", "ReAct 推理失败，切换到普通模式");
                }
            }

            // 发送思考过程：开始生成
            sendThinkingEvent(emitter, "generation", "正在生成回答...");

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

                                    // Save to short-term memory
                                    shortTermMemory.addMessage(sessionId.toString(), assistantMessage);

                                    // 9. Consolidate memory (async)
                                    CompletableFuture.runAsync(() -> {
                                        try {
                                            memoryConsolidation.consolidate(userId, sessionId.toString());
                                            log.info("Memory consolidated for user {}, session {}", userId, sessionId);
                                        } catch (Exception e) {
                                            log.warn("Memory consolidation failed: {}", e.getMessage());
                                        }
                                    });
                                }

                                // 10. Send completion thinking event
                                sendThinkingEvent(emitter, "done", "回答完成");

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
                // 使用 Map 对象，让 Spring 自动序列化
                Map<String, Object> eventData = new HashMap<>();
                eventData.put("type", "content");
                eventData.put("content", content);
                emitter.send(SseEmitter.event()
                        .name("content")
                        .data(eventData));
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
     * Send a thinking event via SSE.
     */
    private void sendThinkingEvent(SseEmitter emitter, String step, String message) throws IOException {
        // 使用 Map 对象，让 Spring 自动序列化
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("step", step);
        eventData.put("message", message);
        emitter.send(SseEmitter.event()
                .name("thinking")
                .data(eventData));
    }

    /**
     * 把输入安全事件写入审计。
     * <p>
     * 修复说明：此前输入校验只打了一行日志 —— 谁在什么时候试图注入什么，没有留下
     * 任何可查询的记录。安全事件的价值恰恰在于「事后能查」，所以这里落到 audit_log：
     * 阻断级记为 blocked=1 + BLOCK 风险等级，警告级记为 blocked=0 + WARN。
     */
    private void auditInput(Long userId, InputValidator.ValidationResult validation, boolean blocked) {
        if (auditService == null) {
            return;
        }
        try {
            List<String> reasons = blocked ? validation.getBlockers() : validation.getWarnings();
            auditService.log(
                    userId,
                    blocked ? "INPUT_BLOCKED" : "INPUT_FLAGGED",
                    "chat:message",
                    blocked ? ToolRiskLevel.BLOCK : ToolRiskLevel.WARN,
                    blocked,
                    reasons == null ? null : String.join(" | ", reasons));
        } catch (Exception e) {
            log.debug("Failed to write input audit log: {}", e.getMessage());
        }
    }

    /**
     * Detect code blocks in the assistant's response and execute them in sandbox.
     * <p>
     * 受 {@code app.chat.auto-execute-code-blocks} 控制（默认关）。
     * 代码执行是有资源成本 + 安全面的动作，不应在用户没要求时自动发生 ——
     * 这也是为什么它必须是一个能被关掉的开关，而不是写死在代码路径里。
     */
    private void executeCodeBlocks(String content, SseEmitter emitter) {
        if (!autoExecuteCodeBlocksR) {
            log.debug("Code block auto-execution disabled (app.chat.auto-execute-code-blocks=false), skipping");
            return;
        }

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
                // 显式声明「未确认」：这条「自动执行 LLM 回答里代码块」的路径绝不能替用户确认，
                // 否则当 app.sandbox.require-confirm=true 时会直接架空确认门槛（安全倒退）。
                // 这里的 setConfirmed(false) 是明示而非遗漏 —— 请勿改成 true。
                req.setConfirmed(false);

                SandboxExecuteResponse response = sandboxService.execute(req);

                // 沙箱总开关关闭（app.sandbox.enabled=false）：服务层未创建/未执行任何容器，
                // 返回带「已禁用」前缀的结果。单独发语义明确的事件，避免把「拒绝执行」
                // 混进正常执行结果里造成误读。
                if (response.getError() != null
                        && response.getError().startsWith(SandboxService.SANDBOX_DISABLED_PREFIX)) {
                    log.warn("Code block not executed: sandbox disabled (app.sandbox.enabled=false)");
                    Map<String, Object> disabledEvent = new HashMap<>();
                    disabledEvent.put("language", language);
                    disabledEvent.put("code", code);
                    disabledEvent.put("output", null);
                    disabledEvent.put("error", "代码块未执行：沙箱已被关闭（app.sandbox.enabled=false）");
                    disabledEvent.put("exitCode", -1);
                    disabledEvent.put("executionTime", 0L);
                    emitter.send(SseEmitter.event()
                            .name("sandbox")
                            .data(disabledEvent));
                    continue;
                }

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
                        .data(sandboxEvent));

                log.info("Sandbox execution completed: exitCode={}, time={}ms",
                        response.getExitCode(), response.getExecutionTime());
            } catch (IllegalArgumentException e) {
                // 安全门槛拒绝（app.sandbox.require-confirm=true 且未确认）：这不是执行故障，
                // 而是「代码块被安全策略挡住」。必须发语义明确的事件，
                // 而不是笼统的 "Execution failed"，否则用户只看到「失败」却不知为何。
                log.warn("Code block not executed: sandbox confirmation required: {}", e.getMessage());
                try {
                    Map<String, Object> blockedEvent = new HashMap<>();
                    blockedEvent.put("language", language);
                    blockedEvent.put("code", code);
                    blockedEvent.put("output", null);
                    blockedEvent.put("error", "代码块未执行：沙箱要求用户确认后才可运行"
                            + "（app.sandbox.require-confirm=true，请在参数中携带 confirmed=true）");
                    blockedEvent.put("exitCode", -1);
                    blockedEvent.put("executionTime", 0L);
                    emitter.send(SseEmitter.event()
                            .name("sandbox")
                            .data(blockedEvent));
                } catch (Exception ignored) {}
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
                            .data(errorEvent));
                } catch (Exception ignored) {}
            }
        }
    }

    private String buildSystemPrompt(Map<String, Object> context,
                                     List<SearchResult> searchResults) {
        // 从模板文件加载基础提示词（带缓存）
        String template = loadSystemPromptTemplate();

        // 合并两路检索结果：
        // - searchResults：本方法调用方（ChatServiceImpl 主流程 + web search）拿到的
        // - context.ragResults：ContextAssembly 自己走 HybridRetrievalService 拿到的
        // 二者此前互不相识，ContextAssembly 那一路查完就被丢弃了（白烧一次向量+BM25 检索）。
        List<SearchResult> merged = mergeSearchResults(searchResults, context);

        // 构建参考资料部分
        StringBuilder contextSection = new StringBuilder();
        if (!merged.isEmpty()) {
            contextSection.append("## 参考资料\n");
            for (int i = 0; i < merged.size(); i++) {
                SearchResult r = merged.get(i);
                contextSection.append("[").append(i + 1).append("] ");
                if (r.getTitle() != null) {
                    contextSection.append(r.getTitle()).append(": ");
                }
                contextSection.append(r.getContent()).append("\n");
            }
        }

        // 构建记忆部分：交给 ContextAssembly 统一渲染，
        // 覆盖 runtimeState（Planner/Tool/任务态）/ userProfile（用户画像）/
        // longTermRecall（相关记忆）/ graphMemoryChain（知识图谱关联）。
        // 修复前这里只读 longTermRecall 一个 key，其余三个组装完就没人用了。
        // ragResults 传 false —— 上面已合并进 contextSection，避免重复列一遍；
        // shortTermMessages 传 false —— 历史消息已由 buildMessages 作为多轮 messages 传入。
        String memorySection = contextAssembly.buildMemorySection(context, false, false);

        // 替换模板中的占位符
        String prompt = template
                .replace("{context}", contextSection.toString())
                .replace("{memory}", memorySection);

        // 如果模板中没有占位符（旧模板兼容），直接拼接
        if (!template.contains("{context}") && !template.contains("{memory}")) {
            StringBuilder fallback = new StringBuilder(template);
            if (contextSection.length() > 0) {
                fallback.append("\n").append(contextSection);
            }
            if (!memorySection.isBlank()) {
                fallback.append("\n").append(memorySection);
            }
            if (!merged.isEmpty()) {
                fallback.append("\n如果用户要求你写代码，请直接写出完整的代码。");
                fallback.append("代码会被自动在沙箱环境中执行并返回结果。\n");
            }
            return fallback.toString();
        }

        return prompt;
    }

    /**
     * 合并两路检索结果，按「标题 + 内容」去重，保留先出现的（本方法调用方的结果优先）。
     */
    private List<SearchResult> mergeSearchResults(List<SearchResult> primary,
                                                  Map<String, Object> context) {
        List<SearchResult> merged = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();

        if (primary != null) {
            for (SearchResult r : primary) {
                if (r == null) {
                    continue;
                }
                String key = (r.getTitle() == null ? "" : r.getTitle()) + "\u0000"
                        + (r.getContent() == null ? "" : r.getContent());
                if (seen.add(key)) {
                    merged.add(r);
                }
            }
        }

        Object ragRaw = context == null ? null : context.get("ragResults");
        if (ragRaw instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof SearchResult r)) {
                    continue;
                }
                String key = (r.getTitle() == null ? "" : r.getTitle()) + "\u0000"
                        + (r.getContent() == null ? "" : r.getContent());
                if (seen.add(key)) {
                    merged.add(r);
                }
            }
        }

        return merged;
    }

    /**
     * 加载系统提示词模板（带缓存）。
     */
    private String loadSystemPromptTemplate() {
        if (cachedSystemPromptTemplate != null) {
            return cachedSystemPromptTemplate;
        }
        synchronized (this) {
            if (cachedSystemPromptTemplate != null) {
                return cachedSystemPromptTemplate;
            }
            try {
                String template = systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
                cachedSystemPromptTemplate = template;
                return template;
            } catch (IOException e) {
                log.error("Failed to load system prompt template, using fallback: {}", e.getMessage());
                return "你是一个智能学习助手。基于提供的上下文和你的知识回答用户的问题。\n\n";
            }
        }
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

    /**
     * 生成<b>请求级唯一</b>的 Harness 任务名。
     * <p>
     * 背景：{@link HarnessRuntime} 以 taskName 作为
     * {@link com.agi.assistant.service.harness.StateMachine} 的 key，每次 {@code execute}
     * 都会先 {@code reset} 再 {@code transition}。若 taskName 是固定常量（如
     * {@code "rag-retrieval"}），并发请求会共享同一 key、互相把对方的状态重置 / 推进，
     * 触发状态机非法转移。
     * <p>
     * 之所以不能只用 {@code base + sessionId}：同一会话内并发发两条消息时 sessionId 相同，
     * 仍会撞车。因此追加一个请求级 UUID，保证「同一会话并发请求」也彼此隔离；sessionId
     * 缺失时由 UUID 兜底。
     * <p>
     * 可见性设为 package-private 且为 static：便于单测在不构造整个 {@code ChatServiceImpl}
     * 的前提下直接验证 key 的唯一性。
     *
     * @param base      任务基础名（如 {@code "rag-retrieval"}）
     * @param sessionId 会话 ID，可为 null
     * @return 形如 {@code base#sessionId#uuid} 的请求级唯一 taskName
     */
    static String taskKey(String base, String sessionId) {
        String sid = (sessionId == null || sessionId.isBlank()) ? "no-session" : sessionId;
        return base + "#" + sid + "#" + UUID.randomUUID();
    }

    /**
     * 把降级供应商返回的 {@code List<?>} 适配成调用点期望的 {@code List<SearchResult>}。
     * <p>
     * {@link ChatFallbackProvider} 的检索降级统一返回 {@code List<Object>}（因为它同时服务
     * 检索与联网搜索两条路），而本类的 execute 调用点需要 {@code List<SearchResult>}。这里
     * 只做<b>类型层面的适配</b>：筛选出 {@link SearchResult} 元素，不改变降级语义，也绝不
     * 编造数据。返回结果永不为 null（最差为空列表），以便 HarnessRuntime 将降级判定为成功。
     *
     * @param raw 降级供应商返回的原始列表，可为 null
     * @return 适配后的 {@code List<SearchResult>}；无匹配元素时为空列表
     */
    private static List<SearchResult> asSearchResults(List<?> raw) {
        List<SearchResult> adapted = new ArrayList<>();
        if (raw == null) {
            return adapted;
        }
        for (Object item : raw) {
            if (item instanceof SearchResult sr) {
                adapted.add(sr);
            }
        }
        return adapted;
    }

    /**
     * 判断是否为复杂问题，需要 ReAct 多步推理。
     * <p>
     * 判定条件（满足任一即为复杂）：
     * 1. 消息长度超过阈值（主条件，阈值来自 app.chat.react-min-length）
     * 2. 包含复杂问题关键词（默认关闭，见 app.chat.react-keywords-enabled）
     * 3. 包含多个问号（默认关闭，见 app.chat.react-multi-question-enabled）
     * <p>
     * 修复说明（P2-3）：条件 2/3 此前无条件生效 —— 关键词表里是
     * 「如何/为什么/explain」这类日常词，导致几乎每个提问都走 ReAct：
     * 多轮 LLM 调用 + 跳过流式输出，延迟与成本双输。现在默认只按长度触发，
     * 需要更激进的触发策略时由配置显式打开。
     */
    private boolean isComplexQuery(String message) {
        if (message == null) return false;

        // 条件 1：消息较长（阈值来自 app.chat.react-min-length）
        if (message.length() > reactMinLength) {
            return true;
        }

        // 条件 2：包含复杂问题关键词（默认关闭）
        if (reactKeywordsEnabled) {
            String lower = message.toLowerCase();
            for (String keyword : COMPLEX_KEYWORDS) {
                if (lower.contains(keyword)) {
                    return true;
                }
            }
        }

        // 条件 3：多个问号（默认关闭）
        if (reactMultiQuestionEnabled) {
            long questionMarks = message.chars().filter(c -> c == '?' || c == '？').count();
            if (questionMarks >= 2) {
                return true;
            }
        }

        return false;
    }
}
