package com.agi.assistant.service.rag;

import com.agi.assistant.model.entity.DocumentMetadata;
import com.agi.assistant.model.entity.ParsedDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Markdown 文档解析服务
 * <p>
 * 负责解析 Markdown 文档，提取元数据（标题、标签、作者、发布日期），
 * 清理正文内容（移除广告、格式化代码块、去除多余空行等）。
 */
@Slf4j
@Service
public class DocumentParser {

    // ──────────────────────────────────────────────────────────────
    //  正则常量
    // ──────────────────────────────────────────────────────────────

    /** YAML front-matter 块 */
    private static final Pattern FRONT_MATTER_PATTERN =
            Pattern.compile("^---\\s*\\n(.*?)\\n---\\s*\\n", Pattern.DOTALL);

    /** ATX 标题（# ~ ######） */
    private static final Pattern HEADING_PATTERN =
            Pattern.compile("(?m)^#{1,6}\\s+(.+)$");

    /** 围栏代码块 ```lang ... ``` */
    private static final Pattern FENCED_CODE_PATTERN =
            Pattern.compile("```(\\w*)\\n(.*?)```", Pattern.DOTALL);

    /** 内联代码 `...` */
    private static final Pattern INLINE_CODE_PATTERN =
            Pattern.compile("`([^`]+)`");

    /** 广告 / 推广链接关键词 */
    private static final List<String> AD_KEYWORDS = Arrays.asList(
            "广告", "推广", "sponsor", "advertisement", "affiliate",
            "关注公众号", "扫码领取", "点击链接", "限时优惠", "免费领取",
            "subscribe", "newsletter", "follow us"
    );

    /** 常见广告 URL 特征 */
    private static final Pattern AD_URL_PATTERN = Pattern.compile(
            "https?://[^\\s)]*(?:promo|ads|click|track|ref|aff)[^\\s)]*",
            Pattern.CASE_INSENSITIVE
    );

    /** 图片引用 ![alt](url) */
    private static final Pattern IMAGE_PATTERN =
            Pattern.compile("!\\[([^\\]]*)\\]\\(([^)]+)\\)");

    /** HTML 标签 */
    private static final Pattern HTML_TAG_PATTERN =
            Pattern.compile("<[^>]+>");

    /** 多余连续空行 */
    private static final Pattern MULTI_BLANK_LINES =
            Pattern.compile("\\n{3,}");

    /** 行尾空白 */
    private static final Pattern TRAILING_WHITESPACE =
            Pattern.compile("[ \\t]+\\n");

    // ──────────────────────────────────────────────────────────────
    //  公共 API
    // ──────────────────────────────────────────────────────────────

    /**
     * 解析 Markdown 文档，返回清洗后的正文与元数据。
     *
     * @param documentId 文档唯一标识
     * @param rawContent 原始 Markdown 内容
     * @return 解析结果
     */
    public ParsedDocument parse(String documentId, String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return ParsedDocument.builder()
                    .documentId(documentId)
                    .cleanedContent("")
                    .rawContent(rawContent)
                    .metadata(DocumentMetadata.builder().build())
                    .build();
        }

        log.debug("Parsing document [{}], raw length={}", documentId, rawContent.length());

        // 1. 提取 front-matter
        String frontMatterBlock = extractFrontMatter(rawContent);
        String contentWithoutFrontMatter = stripFrontMatter(rawContent);

        // 2. 解析元数据（优先从 front-matter，其次从正文推断）
        DocumentMetadata metadata = parseMetadata(contentWithoutFrontMatter, frontMatterBlock);

        // 3. 清洗正文
        String cleanedContent = cleanContent(contentWithoutFrontMatter);

        log.debug("Document [{}] parsed: title={}, cleaned length={}",
                documentId, metadata.getTitle(), cleanedContent.length());

        return ParsedDocument.builder()
                .documentId(documentId)
                .cleanedContent(cleanedContent)
                .rawContent(rawContent)
                .metadata(metadata)
                .build();
    }

    /**
     * 批量解析文档。
     */
    public List<ParsedDocument> parseBatch(Map<String, String> documents) {
        List<ParsedDocument> results = new ArrayList<>(documents.size());
        for (Map.Entry<String, String> entry : documents.entrySet()) {
            results.add(parse(entry.getKey(), entry.getValue()));
        }
        return results;
    }

    /**
     * 仅提取元数据（不清洗正文），适用于快速索引场景。
     */
    public DocumentMetadata extractMetadata(String rawContent) {
        String frontMatterBlock = extractFrontMatter(rawContent);
        String contentWithoutFrontMatter = stripFrontMatter(rawContent);
        return parseMetadata(contentWithoutFrontMatter, frontMatterBlock);
    }

    /**
     * 仅清洗正文（不提取元数据）。
     */
    public String cleanContent(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }

        String result = markdown;

        // 1. 移除广告段落
        result = removeAdParagraphs(result);

        // 2. 移除广告 URL
        result = AD_URL_PATTERN.matcher(result).replaceAll("");

        // 3. 格式化代码块（保留语言标记，去除多余缩进）
        result = formatCodeBlocks(result);

        // 4. 清理行尾空白
        result = TRAILING_WHITESPACE.matcher(result).replaceAll("\n");

        // 5. 移除多余连续空行
        result = MULTI_BLANK_LINES.matcher(result).replaceAll("\n\n");

        // 6. 去除首尾空白
        result = result.strip();

        return result;
    }

    /**
     * 从 Markdown 正文中提取所有标题，用于语义分块时的分段。
     *
     * @param markdown Markdown 文本
     * @return 标题列表，按出现顺序排列
     */
    public List<String> extractHeadings(String markdown) {
        if (markdown == null) {
            return Collections.emptyList();
        }
        List<String> headings = new ArrayList<>();
        Matcher m = HEADING_PATTERN.matcher(markdown);
        while (m.find()) {
            headings.add(m.group(1).trim());
        }
        return headings;
    }

    /**
     * 按标题层级将 Markdown 拆分为多个段落。
     * 每个段落以一级或二级标题开始。
     *
     * @param markdown Markdown 文本
     * @return 段落列表
     */
    public List<String> splitByHeadings(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return Collections.emptyList();
        }

        // 匹配 # 或 ## 开头的行作为分割点
        Pattern splitPattern = Pattern.compile("(?m)^(#{1,2}\\s+.+)$");
        Matcher matcher = splitPattern.matcher(markdown);

        List<String> sections = new ArrayList<>();
        int lastEnd = 0;
        int lastMatchStart = -1;

        while (matcher.find()) {
            if (lastMatchStart >= 0) {
                String section = markdown.substring(lastMatchStart, matcher.start()).strip();
                if (!section.isEmpty()) {
                    sections.add(section);
                }
            } else {
                // 第一个标题之前的内容
                String prelude = markdown.substring(0, matcher.start()).strip();
                if (!prelude.isEmpty()) {
                    sections.add(prelude);
                }
            }
            lastMatchStart = matcher.start();
        }

        // 最后一段
        if (lastMatchStart >= 0) {
            String tail = markdown.substring(lastMatchStart).strip();
            if (!tail.isEmpty()) {
                sections.add(tail);
            }
        }

        // 如果没有标题，则将整个文档作为一个段落
        if (sections.isEmpty()) {
            sections.add(markdown.strip());
        }

        return sections;
    }

    // ──────────────────────────────────────────────────────────────
    //  内部方法 —— 元数据解析
    // ──────────────────────────────────────────────────────────────

    /**
     * 解析文档元数据：优先使用 YAML front-matter，再从正文推断。
     */
    private DocumentMetadata parseMetadata(String content, String frontMatterBlock) {
        DocumentMetadata.DocumentMetadataBuilder builder = DocumentMetadata.builder();

        if (frontMatterBlock != null && !frontMatterBlock.isBlank()) {
            parseFrontMatter(frontMatterBlock, builder);
        }

        // 若 front-matter 中缺失字段，从正文推断
        if (builder.build().getTitle() == null) {
            builder.title(inferTitle(content));
        }
        if (builder.build().getTags() == null || builder.build().getTags().isEmpty()) {
            builder.tags(inferTags(content));
        }

        return builder.build();
    }

    /**
     * 解析 YAML front-matter 内容。
     */
    private void parseFrontMatter(String yaml, DocumentMetadata.DocumentMetadataBuilder builder) {
        Map<String, String> kvPairs = parseSimpleYaml(yaml);

        if (kvPairs.containsKey("title")) {
            builder.title(kvPairs.get("title").replace("\"", "").replace("'", "").trim());
        }
        if (kvPairs.containsKey("author")) {
            builder.author(kvPairs.get("author").replace("\"", "").replace("'", "").trim());
        }
        if (kvPairs.containsKey("date") || kvPairs.containsKey("publishDate")) {
            String dateStr = kvPairs.getOrDefault("date", kvPairs.get("publishDate"));
            builder.publishDate(parseDate(dateStr));
        }
        if (kvPairs.containsKey("tags")) {
            builder.tags(parseYamlList(kvPairs.get("tags")));
        }
        if (kvPairs.containsKey("source") || kvPairs.containsKey("url")) {
            builder.sourceUrl(kvPairs.getOrDefault("source", kvPairs.get("url")));
        }
        if (kvPairs.containsKey("lang") || kvPairs.containsKey("language")) {
            builder.language(kvPairs.getOrDefault("lang", kvPairs.get("language")));
        }
        if (kvPairs.containsKey("summary") || kvPairs.containsKey("description")) {
            builder.summary(kvPairs.getOrDefault("summary", kvPairs.get("description")));
        }
    }

    /**
     * 简易 YAML key: value 解析（仅处理单层 key-value）。
     */
    private Map<String, String> parseSimpleYaml(String yaml) {
        Map<String, String> result = new HashMap<>();
        for (String line : yaml.split("\\n")) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int colonIdx = line.indexOf(':');
            if (colonIdx > 0) {
                String key = line.substring(0, colonIdx).strip();
                String value = line.substring(colonIdx + 1).strip();
                result.put(key, value);
            }
        }
        return result;
    }

    /**
     * 解析 YAML 列表值，支持 [a, b, c] 和多行 - a 格式。
     */
    private List<String> parseYamlList(String value) {
        if (value == null || value.isBlank()) {
            return Collections.emptyList();
        }
        value = value.strip();

        // [a, b, c] 格式
        if (value.startsWith("[") && value.endsWith("]")) {
            String inner = value.substring(1, value.length() - 1);
            return Arrays.stream(inner.split(","))
                    .map(s -> s.replace("\"", "").replace("'", "").trim())
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        }

        // 单个值
        return List.of(value.replace("\"", "").replace("'", "").trim());
    }

    /**
     * 解析日期字符串，支持多种常见格式。
     */
    private LocalDateTime parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        dateStr = dateStr.replace("\"", "").replace("'", "").strip();

        List<DateTimeFormatter> formatters = List.of(
                DateTimeFormatter.ISO_LOCAL_DATE_TIME,
                DateTimeFormatter.ISO_LOCAL_DATE,
                DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy/MM/dd"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd"),
                DateTimeFormatter.ofPattern("dd-MM-yyyy"),
                DateTimeFormatter.ofPattern("MM/dd/yyyy")
        );

        for (DateTimeFormatter fmt : formatters) {
            try {
                LocalDateTime ldt = LocalDateTime.parse(dateStr, fmt);
                return ldt;
            } catch (DateTimeParseException ignored) {
                // 尝试下一种格式
            }
            // 仅日期格式时 LocalDateTime.parse 会失败，用 LocalDate 再试
            try {
                java.time.LocalDate ld = java.time.LocalDate.parse(dateStr, fmt);
                return ld.atStartOfDay();
            } catch (DateTimeParseException ignored) {
                // 继续
            }
        }
        log.warn("Unable to parse date string: {}", dateStr);
        return null;
    }

    /**
     * 从正文中推断标题：取第一个 # 标题或第一个非空行。
     */
    private String inferTitle(String content) {
        if (content == null || content.isBlank()) {
            return "Untitled";
        }
        // 尝试取第一个 ATX 标题
        Matcher m = HEADING_PATTERN.matcher(content);
        if (m.find()) {
            return m.group(1).trim();
        }
        // 取第一个非空行（截断到 120 字符）
        for (String line : content.split("\\n")) {
            String stripped = line.strip();
            if (!stripped.isEmpty()) {
                return stripped.length() > 120 ? stripped.substring(0, 120) + "..." : stripped;
            }
        }
        return "Untitled";
    }

    /**
     * 从正文中推断标签：识别常见标签标记或关键词。
     */
    private List<String> inferTags(String content) {
        if (content == null) {
            return Collections.emptyList();
        }

        List<String> tags = new ArrayList<>();

        // 查找 "tags:" 或 "标签:" 行
        Pattern tagLinePattern = Pattern.compile("(?m)^(?:tags|标签)[：:]\\s*(.+)$", Pattern.CASE_INSENSITIVE);
        Matcher m = tagLinePattern.matcher(content);
        if (m.find()) {
            String tagStr = m.group(1);
            for (String tag : tagStr.split("[,，、;；]")) {
                String t = tag.strip();
                if (!t.isEmpty()) {
                    tags.add(t);
                }
            }
        }

        return tags;
    }

    // ──────────────────────────────────────────────────────────────
    //  内部方法 —— 内容清洗
    // ──────────────────────────────────────────────────────────────

    /**
     * 提取 YAML front-matter 块。
     */
    private String extractFrontMatter(String content) {
        Matcher m = FRONT_MATTER_PATTERN.matcher(content);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /**
     * 移除 YAML front-matter 块。
     */
    private String stripFrontMatter(String content) {
        return FRONT_MATTER_PATTERN.matcher(content).replaceFirst("");
    }

    /**
     * 移除包含广告关键词的段落。
     */
    private String removeAdParagraphs(String content) {
        String[] paragraphs = content.split("\\n\\n+");
        StringBuilder sb = new StringBuilder();

        for (String paragraph : paragraphs) {
            if (isAdParagraph(paragraph)) {
                log.debug("Removing ad paragraph: {}",
                        paragraph.length() > 80 ? paragraph.substring(0, 80) + "..." : paragraph);
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(paragraph);
        }

        return sb.toString();
    }

    /**
     * 判断段落是否为广告内容。
     */
    private boolean isAdParagraph(String paragraph) {
        String lower = paragraph.toLowerCase();
        for (String keyword : AD_KEYWORDS) {
            if (lower.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 格式化围栏代码块：确保语言标记正确，去除代码块内的多余缩进。
     */
    private String formatCodeBlocks(String content) {
        Matcher m = FENCED_CODE_PATTERN.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String lang = m.group(1);
            String code = m.group(2);

            // 去除代码块内首尾空行
            code = code.stripLeading();
            if (code.endsWith("\n")) {
                code = code.substring(0, code.length() - 1);
            }

            String replacement = "```" + lang + "\n" + code + "\n```";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
