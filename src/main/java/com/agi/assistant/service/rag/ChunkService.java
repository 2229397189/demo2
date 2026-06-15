package com.agi.assistant.service.rag;

import com.agi.assistant.model.entity.DocumentChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档分块服务
 * <p>
 * 提供两种分块策略：
 * <ul>
 *   <li>固定长度分块（chunkByFixedLength）—— 按 token 数切分，支持重叠窗口</li>
 *   <li>语义分块（chunkBySemantic）—— 按段落 / 标题等语义边界切分</li>
 * </ul>
 */
@Slf4j
@Service
public class ChunkService {

    /** 默认块大小（token 近似值，1 中文字符 ≈ 1 token，1 英文单词 ≈ 1 token） */
    private static final int DEFAULT_CHUNK_SIZE = 512;

    /** 默认重叠窗口大小 */
    private static final int DEFAULT_OVERLAP_SIZE = 50;

    /** 最小块大小，避免产生过小的碎片 */
    private static final int MIN_CHUNK_SIZE = 50;

    /** 标题模式：匹配 # ~ ###### */
    private static final Pattern HEADING_PATTERN =
            Pattern.compile("(?m)^#{1,6}\\s+.+$");

    /** 段落分隔符（连续两个换行） */
    private static final Pattern PARAGRAPH_SEPARATOR = Pattern.compile("\\n\\n+");

    /** 句子结束标记 */
    private static final Pattern SENTENCE_END = Pattern.compile("[。！？.!?\\n]");

    // ──────────────────────────────────────────────────────────────
    //  公共 API
    // ──────────────────────────────────────────────────────────────

    /**
     * 固定长度分块（使用默认参数：512 tokens，50 tokens 重叠）。
     *
     * @param documentId 文档 ID
     * @param content    文本内容
     * @return 分块结果列表
     */
    public List<DocumentChunk> chunkByFixedLength(String documentId, String content) {
        return chunkByFixedLength(documentId, content, DEFAULT_CHUNK_SIZE, DEFAULT_OVERLAP_SIZE);
    }

    /**
     * 固定长度分块。
     * <p>
     * 按照指定的 token 数切分文本，相邻块之间保留 overlap 个 token 的重叠区域，
     * 以保证上下文连贯性。切分时优先在句子或段落边界处断开。
     *
     * @param documentId 文档 ID
     * @param content    文本内容
     * @param chunkSize  每块的目标 token 数
     * @param overlap    相邻块重叠的 token 数
     * @return 分块结果列表
     */
    public List<DocumentChunk> chunkByFixedLength(String documentId, String content,
                                                  int chunkSize, int overlap) {
        if (content == null || content.isBlank()) {
            return Collections.emptyList();
        }

        chunkSize = Math.max(chunkSize, MIN_CHUNK_SIZE);
        overlap = Math.max(0, Math.min(overlap, chunkSize / 2));

        List<DocumentChunk> chunks = new ArrayList<>();
        String normalizedContent = normalizeText(content);
        int totalLength = normalizedContent.length();
        int position = 0;
        int chunkIndex = 0;

        while (position < totalLength) {
            // 计算当前块的结束位置
            int endPosition = Math.min(position + chunkSize, totalLength);

            // 尝试在句子边界处断开（仅在非最后一块时）
            if (endPosition < totalLength) {
                int breakPoint = findBreakPoint(normalizedContent, position, endPosition);
                if (breakPoint > position) {
                    endPosition = breakPoint;
                }
            }

            // 提取块内容
            String chunkContent = normalizedContent.substring(position, endPosition).strip();

            if (!chunkContent.isEmpty()) {
                DocumentChunk chunk = buildChunk(documentId, chunkIndex, chunkContent,
                        position, endPosition);
                chunks.add(chunk);
                chunkIndex++;
            }

            // 移动到下一个位置（考虑重叠）
            int advance = endPosition - position - overlap;
            position += Math.max(advance, 1);
        }

        log.debug("Fixed-length chunking for document [{}]: {} chunks (size={}, overlap={})",
                documentId, chunks.size(), chunkSize, overlap);

        return chunks;
    }

    /**
     * 语义分块（使用默认参数）。
     * <p>
     * 按段落和标题边界切分，合并过小的段落，拆分过大的段落。
     *
     * @param documentId 文档 ID
     * @param content    文本内容
     * @return 分块结果列表
     */
    public List<DocumentChunk> chunkBySemantic(String documentId, String content) {
        return chunkBySemantic(documentId, content, DEFAULT_CHUNK_SIZE, DEFAULT_OVERLAP_SIZE);
    }

    /**
     * 语义分块。
     * <p>
     * 按段落和标题等语义边界切分文本。合并过小的段落直到达到目标大小，
     * 拆分过大的段落时优先在句子边界处断开。
     *
     * @param documentId    文档 ID
     * @param content       文本内容
     * @param maxChunkSize  单块最大 token 数
     * @param overlap       相邻块重叠的 token 数
     * @return 分块结果列表
     */
    public List<DocumentChunk> chunkBySemantic(String documentId, String content,
                                               int maxChunkSize, int overlap) {
        if (content == null || content.isBlank()) {
            return Collections.emptyList();
        }

        maxChunkSize = Math.max(maxChunkSize, MIN_CHUNK_SIZE);
        overlap = Math.max(0, Math.min(overlap, maxChunkSize / 2));

        String normalizedContent = normalizeText(content);

        // 第一步：按段落分割
        List<String> paragraphs = splitIntoParagraphs(normalizedContent);

        // 第二步：合并小段落，拆分大段落
        List<String> mergedChunks = mergeAndSplit(paragraphs, maxChunkSize);

        // 第三步：添加重叠上下文
        List<String> overlappingChunks = addOverlap(mergedChunks, overlap);

        // 第四步：构建 DocumentChunk 列表
        List<DocumentChunk> chunks = new ArrayList<>();
        int currentPosition = 0;
        for (int i = 0; i < overlappingChunks.size(); i++) {
            String chunkContent = overlappingChunks.get(i).strip();
            if (chunkContent.isEmpty()) {
                continue;
            }

            // 查找该块在原文中的大致位置
            int startPos = normalizedContent.indexOf(chunkContent.substring(
                    0, Math.min(50, chunkContent.length())), currentPosition);
            if (startPos < 0) {
                startPos = currentPosition;
            }
            int endPos = startPos + chunkContent.length();

            DocumentChunk chunk = buildChunk(documentId, i, chunkContent, startPos, endPos);
            chunks.add(chunk);
            currentPosition = Math.max(currentPosition, endPos - overlap);
        }

        log.debug("Semantic chunking for document [{}]: {} chunks (maxSize={}, overlap={})",
                documentId, chunks.size(), maxChunkSize, overlap);

        return chunks;
    }

    /**
     * 估算文本的 token 数量。
     * 粗略规则：中文 1 字符 ≈ 1 token，英文 1 单词 ≈ 1 token。
     */
    public int estimateTokenCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int count = 0;
        boolean inWord = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isChinese(c)) {
                // 中文字符按 1 token 计
                count++;
                inWord = false;
            } else if (Character.isLetterOrDigit(c)) {
                if (!inWord) {
                    count++;
                    inWord = true;
                }
            } else {
                inWord = false;
            }
        }

        return count;
    }

    // ──────────────────────────────────────────────────────────────
    //  内部方法
    // ──────────────────────────────────────────────────────────────

    /**
     * 构建 DocumentChunk 对象。
     */
    private DocumentChunk buildChunk(String documentId, int chunkIndex, String content,
                                     int startOffset, int endOffset) {
        DocumentChunk chunk = new DocumentChunk();
        if (documentId != null) {
            try {
                chunk.setDocumentId(Long.parseLong(documentId));
            } catch (NumberFormatException e) {
                log.warn("Cannot parse documentId [{}] as Long, setting null", documentId);
                chunk.setDocumentId(null);
            }
        }
        chunk.setChunkIndex(chunkIndex);
        chunk.setContent(content);
        chunk.setTokenCount(estimateTokenCount(content));
        return chunk;
    }

    /**
     * 标准化文本：统一换行符，去除多余空白。
     */
    private String normalizeText(String text) {
        // 统一换行符
        text = text.replace("\r\n", "\n").replace("\r", "\n");
        // 去除每行首尾空白（保留段落间的空行）
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\\n")) {
            sb.append(line.stripTrailing()).append("\n");
        }
        return sb.toString().strip();
    }

    /**
     * 在 [start, end] 范围内寻找最佳断点。
     * 优先级：段落边界 > 句子边界 > 空格
     */
    private int findBreakPoint(String text, int start, int end) {
        // 从 end 向前搜索最近的断句点
        int searchStart = Math.max(start, end - 100);

        // 先找段落边界
        for (int i = end - 1; i >= searchStart; i--) {
            if (i + 1 < text.length() && text.charAt(i) == '\n' && text.charAt(i + 1) == '\n') {
                return i + 1;
            }
        }

        // 再找句子边界
        for (int i = end - 1; i >= searchStart; i--) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?') {
                return i + 1;
            }
        }

        // 最后找空格
        for (int i = end - 1; i >= searchStart; i--) {
            if (text.charAt(i) == ' ' || text.charAt(i) == '\n') {
                return i + 1;
            }
        }

        return end;
    }

    /**
     * 将文本按段落分割。
     */
    private List<String> splitIntoParagraphs(String text) {
        String[] rawParagraphs = PARAGRAPH_SEPARATOR.split(text);
        List<String> result = new ArrayList<>();
        for (String p : rawParagraphs) {
            String trimmed = p.strip();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * 合并小段落，拆分大段落。
     */
    private List<String> mergeAndSplit(List<String> paragraphs, int maxSize) {
        List<String> result = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();

        for (String paragraph : paragraphs) {
            int paragraphTokens = estimateTokenCount(paragraph);

            // 单个段落已超限：需要拆分
            if (paragraphTokens > maxSize) {
                // 先把 buffer 中已积累的内容输出
                if (buffer.length() > 0) {
                    result.add(buffer.toString().strip());
                    buffer.setLength(0);
                }
                // 拆分大段落
                result.addAll(splitLargeParagraph(paragraph, maxSize));
                continue;
            }

            // 合并：当前 buffer + 新段落 是否超限
            int bufferTokens = estimateTokenCount(buffer.toString());
            if (bufferTokens + paragraphTokens > maxSize && buffer.length() > 0) {
                result.add(buffer.toString().strip());
                buffer.setLength(0);
            }

            if (buffer.length() > 0) {
                buffer.append("\n\n");
            }
            buffer.append(paragraph);
        }

        // 输出剩余
        if (buffer.length() > 0) {
            result.add(buffer.toString().strip());
        }

        return result;
    }

    /**
     * 拆分过大的段落：按句子拆分，合并到不超过 maxSize。
     */
    private List<String> splitLargeParagraph(String paragraph, int maxSize) {
        List<String> sentences = splitIntoSentences(paragraph);
        List<String> result = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();

        for (String sentence : sentences) {
            int bufferTokens = estimateTokenCount(buffer.toString());
            int sentenceTokens = estimateTokenCount(sentence);

            if (bufferTokens + sentenceTokens > maxSize && buffer.length() > 0) {
                result.add(buffer.toString().strip());
                buffer.setLength(0);
            }

            // 单个句子仍然超限，直接按字符硬切
            if (sentenceTokens > maxSize) {
                if (buffer.length() > 0) {
                    result.add(buffer.toString().strip());
                    buffer.setLength(0);
                }
                result.addAll(hardSplit(sentence, maxSize));
                continue;
            }

            if (buffer.length() > 0) {
                buffer.append(" ");
            }
            buffer.append(sentence);
        }

        if (buffer.length() > 0) {
            result.add(buffer.toString().strip());
        }

        return result;
    }

    /**
     * 将段落拆分为句子。
     */
    private List<String> splitIntoSentences(String text) {
        List<String> sentences = new ArrayList<>();
        Matcher m = SENTENCE_END.matcher(text);
        int start = 0;
        while (m.find()) {
            String sentence = text.substring(start, m.end()).strip();
            if (!sentence.isEmpty()) {
                sentences.add(sentence);
            }
            start = m.end();
        }
        // 剩余部分
        if (start < text.length()) {
            String tail = text.substring(start).strip();
            if (!tail.isEmpty()) {
                sentences.add(tail);
            }
        }
        return sentences;
    }

    /**
     * 按字符硬切分（最后手段）。
     */
    private List<String> hardSplit(String text, int maxSize) {
        List<String> result = new ArrayList<>();
        int pos = 0;
        while (pos < text.length()) {
            int end = Math.min(pos + maxSize, text.length());
            result.add(text.substring(pos, end));
            pos = end;
        }
        return result;
    }

    /**
     * 为块列表添加重叠上下文。
     */
    private List<String> addOverlap(List<String> chunks, int overlap) {
        if (overlap <= 0 || chunks.size() <= 1) {
            return chunks;
        }

        List<String> result = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            StringBuilder sb = new StringBuilder();

            // 从前一个块取尾部作为前缀
            if (i > 0) {
                String prevChunk = chunks.get(i - 1);
                String overlapPrefix = getTail(prevChunk, overlap);
                if (!overlapPrefix.isEmpty()) {
                    sb.append(overlapPrefix).append("\n");
                }
            }

            sb.append(chunks.get(i));
            result.add(sb.toString());
        }
        return result;
    }

    /**
     * 获取文本末尾大约 tokenCount 个 token 的内容。
     */
    private String getTail(String text, int tokenCount) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        // 简单策略：从末尾取约 tokenCount 个字符（中文近似 1 字 = 1 token）
        int charCount = Math.min(tokenCount, text.length());
        return text.substring(text.length() - charCount);
    }

    /**
     * 判断字符是否为中文。
     */
    private boolean isChinese(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }
}
