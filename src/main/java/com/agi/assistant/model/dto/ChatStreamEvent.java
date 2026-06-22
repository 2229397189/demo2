package com.agi.assistant.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatStreamEvent {

    /**
     * 事件类型: thinking, content, source, done
     */
    private String type;

    /**
     * 事件内容，类型取决于type
     */
    private Object content;

    public static ChatStreamEvent thinking(String content) {
        return new ChatStreamEvent("thinking", content);
    }

    public static ChatStreamEvent thinking(String step, String message) {
        return new ChatStreamEvent("thinking", Map.of("step", step, "message", message));
    }

    public static ChatStreamEvent content(String text) {
        return new ChatStreamEvent("content", text);
    }

    public static ChatStreamEvent source(Object sources) {
        return new ChatStreamEvent("source", sources);
    }

    public static ChatStreamEvent done() {
        return new ChatStreamEvent("done", null);
    }
}
