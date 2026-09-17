package com.agi.assistant.service.memory;

import com.agi.assistant.model.entity.ChatMessage;
import com.agi.assistant.service.memory.MemoryConsolidation.ExtractionOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MemoryConsolidation} 记忆整合水位的离线单测。
 * <p>
 * 缺陷：抽取失败时旧实现仍然推进水位 → 那批消息再也不会被重新抽取 = 永久丢记忆。
 * 修复后：只有抽取「成功」才推进水位；失败保持不动（下次重试）。
 *
 * @author Alex
 */
class MemoryConsolidationWatermarkTest {

    @Test
    @DisplayName("抽取失败 → 水位【不】推进（下次重试）")
    void extractionFailureKeepsWatermark() {
        ShortTermMemory stm = mock(ShortTermMemory.class);
        when(stm.getRecentMessages("s", 20)).thenReturn(twoMessages());
        when(stm.getConsolidationWatermark("s")).thenReturn(null);

        MemoryConsolidation consolidation =
                new TestableConsolidation(stm, ExtractionOutcome.failure("LLM 不可用"));

        consolidation.consolidate(1L, "s");

        verify(stm, never()).setConsolidationWatermark(anyString(), anyString());
    }

    @Test
    @DisplayName("抽取成功（即便没抽到事实）→ 水位推进")
    void extractionSuccessAdvancesWatermark() {
        ShortTermMemory stm = mock(ShortTermMemory.class);
        when(stm.getRecentMessages("s", 20)).thenReturn(twoMessages());
        when(stm.getConsolidationWatermark("s")).thenReturn(null);

        MemoryConsolidation consolidation =
                new TestableConsolidation(stm, ExtractionOutcome.success(List.of()));

        consolidation.consolidate(1L, "s");

        verify(stm).setConsolidationWatermark(eq("s"), anyString());
    }

    private static List<ChatMessage> twoMessages() {
        ChatMessage m1 = new ChatMessage();
        m1.setRole("user");
        m1.setContent("我平时更喜欢用 Java 写后端");
        ChatMessage m2 = new ChatMessage();
        m2.setRole("assistant");
        m2.setContent("好的，记住了");
        return List.of(m1, m2);
    }

    /**
     * 可注入抽取结果的测试替身：把「LLM 抽取」替换为固定结果，从而离线覆盖水位逻辑。
     */
    private static final class TestableConsolidation extends MemoryConsolidation {

        private final ExtractionOutcome outcome;

        private TestableConsolidation(ShortTermMemory shortTermMemory, ExtractionOutcome outcome) {
            // 只用到 shortTermMemory；其余协作者传 null（不走 persist 路径）
            super(shortTermMemory, null, null, null, null, null, null);
            this.outcome = outcome;
        }

        @Override
        protected ExtractionOutcome extractFactsDetailed(String conversation) {
            return outcome;
        }
    }
}
