package com.agi.assistant.service.memory;

import com.agi.assistant.mapper.MemoryMapper;
import com.agi.assistant.model.entity.Memory;
import com.agi.assistant.service.rag.EmbeddingService;
import com.agi.assistant.service.rag.MilvusService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link LongTermMemory#saveMemory} 写入归一单测：传入小写 {@code type} 也会存成大写规范 token。
 *
 * @author Alex
 */
class LongTermMemoryTypeTest {

    @Test
    @DisplayName("写入时传入小写 type=fact 也会被存成 FACT")
    void saveMemoryNormalizesTypeToUppercase() {
        MemoryMapper memoryMapper = mock(MemoryMapper.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        MilvusService milvusService = mock(MilvusService.class);

        when(memoryMapper.selectCount(any())).thenReturn(0L);
        // 空 embedding → 跳过向量库写入与相似度去重，聚焦 type 归一
        when(embeddingService.embed(anyString())).thenReturn(List.of());

        LongTermMemory longTermMemory = new LongTermMemory(memoryMapper, embeddingService, milvusService);
        longTermMemory.saveMemory(1L, "用户喜欢喝咖啡", "fact", 0.8, null);

        ArgumentCaptor<Memory> captor = ArgumentCaptor.forClass(Memory.class);
        verify(memoryMapper).insert(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("FACT");
    }

    @Test
    @DisplayName("写入未知取值 type=long_term 时归一回退为 FACT（杜绝库里出现幽灵值）")
    void saveMemoryFallsBackForUnknownType() {
        MemoryMapper memoryMapper = mock(MemoryMapper.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        MilvusService milvusService = mock(MilvusService.class);

        when(memoryMapper.selectCount(any())).thenReturn(0L);
        when(embeddingService.embed(anyString())).thenReturn(List.of());

        LongTermMemory longTermMemory = new LongTermMemory(memoryMapper, embeddingService, milvusService);
        longTermMemory.saveMemory(1L, "某条记忆", "long_term", 0.5, null);

        ArgumentCaptor<Memory> captor = ArgumentCaptor.forClass(Memory.class);
        verify(memoryMapper).insert(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("FACT");
    }
}
