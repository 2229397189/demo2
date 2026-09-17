package com.agi.assistant.service.evaluation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * RAGAS 指标的<b>纯函数</b>聚合层。
 * <p>
 * 本类只负责「已知子判断结果 → 数学聚合」，不依赖 Spring、不发起任何 IO、
 * 不做随机化，因此可在完全离线的环境下用固定输入做确定性单测。
 * 子判断（claim 分解、是否被支持、chunk 是否相关、反向生成问题）由
 * {@link com.agi.assistant.service.evaluation.llm.LlmJudge} 提供，二者彻底分离。
 * <p>
 * 约定：所有「不可用 / 无法计算」的情况返回 {@code -1.0}；绝不返回 0 / 0.5 等占位数字。
 *
 * @author Alex
 */
public final class RagasScorer {

    /** 不可用 / 无法计算时的哨兵值，与评测链路既有约定一致。 */
    public static final double UNAVAILABLE = -1.0;

    // CJK 统一表意文字及扩展区（与 EmbeddingService.tokenize 口径保持一致）
    private static final int CJK_BASE = 0x4E00;
    private static final int CJK_END = 0x9FFF;
    private static final int CJK_EXT_A_BASE = 0x3400;
    private static final int CJK_EXT_A_END = 0x4DBF;
    private static final int CJK_COMPAT_BASE = 0xF900;
    private static final int CJK_COMPAT_END = 0xFAFF;

    private RagasScorer() {
        // 工具类禁止实例化
    }

    // ──────────────────────────────────────────────────────────────
    //  Faithfulness / Context Recall —— 均为「被支持 claim 占比」
    // ──────────────────────────────────────────────────────────────

    /**
     * Faithfulness：被上下文支持的 claim 数 / claim 总数。
     *
     * @param claims 由答案分解出的原子事实声明（supported 字段已回填核验结果）
     * @return 忠实度；{@code claims} 为 null 或空时返回 {@code 1.0}
     *         （空答案没有任何可被证伪的声明，视为无幻觉）
     */
    public static double faithfulness(List<Claim> claims) {
        if (claims == null || claims.isEmpty()) {
            return 1.0;
        }
        long supported = claims.stream().filter(Claim::isSupported).count();
        return (double) supported / claims.size();
    }

    /**
     * Context Recall：被检索上下文覆盖的 golden claim 数 / golden claim 总数。
     *
     * @param goldenClaims 由期望答案分解出的原子事实声明（supported 字段已回填覆盖结果）
     * @return 召回率；{@code goldenClaims} 为 null 或空时返回 {@code 1.0}
     */
    public static double contextRecall(List<Claim> goldenClaims) {
        if (goldenClaims == null || goldenClaims.isEmpty()) {
            return 1.0;
        }
        long covered = goldenClaims.stream().filter(Claim::isSupported).count();
        return (double) covered / goldenClaims.size();
    }

    // ──────────────────────────────────────────────────────────────
    //  Context Precision —— AP@K
    // ──────────────────────────────────────────────────────────────

    /**
     * Average Precision @ K（RAGAS Context Precision 的无截断版本）。
     * <p>
     * 公式：{@code AP@K = Σ_{k=1..K}(P@k · rel_k) / Σ rel_k}，
     * 其中 {@code P@k} = 前 k 个结果中相关结果数 / k，{@code rel_k} 为第 k 个是否相关。
     *
     * @param relevanceAtK 按检索排名从 1 到 K 的相关性判定
     * @return AP@K；无任何相关项（或列表为空）时返回 {@code 0.0}
     */
    public static double averagePrecision(List<Boolean> relevanceAtK) {
        if (relevanceAtK == null || relevanceAtK.isEmpty()) {
            return 0.0;
        }
        int totalRelevant = 0;
        for (Boolean rel : relevanceAtK) {
            if (Boolean.TRUE.equals(rel)) {
                totalRelevant++;
            }
        }
        if (totalRelevant == 0) {
            return 0.0;
        }
        double sum = 0.0;
        int relevantSoFar = 0;
        for (int i = 0; i < relevanceAtK.size(); i++) {
            if (Boolean.TRUE.equals(relevanceAtK.get(i))) {
                relevantSoFar++;
                // P@(i+1) = 前 i+1 个里相关数 / (i+1)
                sum += (double) relevantSoFar / (i + 1);
            }
        }
        return sum / totalRelevant;
    }

    // ──────────────────────────────────────────────────────────────
    //  Answer Relevancy
    // ──────────────────────────────────────────────────────────────

    /**
     * Answer Relevancy（向量版）：原问题与「答案反向生成的问题」两两余弦相似度的算术平均。
     *
     * @param generatedQVecs 反向生成问题的向量列表
     * @param originalQVec   原问题向量
     * @return 平均余弦相似度；向量列表为空 / 维度不一致 / 存在零向量时返回 {@code -1.0}
     */
    public static double answerRelevancy(List<double[]> generatedQVecs, double[] originalQVec) {
        if (generatedQVecs == null || generatedQVecs.isEmpty() || originalQVec == null
                || originalQVec.length == 0) {
            return UNAVAILABLE;
        }
        double sum = 0.0;
        int count = 0;
        for (double[] vec : generatedQVecs) {
            if (vec == null || vec.length != originalQVec.length) {
                return UNAVAILABLE;
            }
            double cos = cosine(vec, originalQVec);
            if (Double.isNaN(cos)) {
                return UNAVAILABLE;
            }
            sum += cos;
            count++;
        }
        return count == 0 ? UNAVAILABLE : sum / count;
    }

    /**
     * Answer Relevancy 的<b>本地确定性回退</b>：embedding 不可用时用 Jaccard 相似度近似。
     * <p>
     * 分词口径（写死以保证可复现）：
     * <ul>
     *   <li>统一转小写；</li>
     *   <li>ASCII 字母 / 数字 / 下划线连续段切为一个 token；</li>
     *   <li>CJK 字符取「单字」+「相邻双字 bigram」（双字保留部分短语信息）；</li>
     *   <li>其余字符（空白、标点）作为分隔符丢弃。</li>
     * </ul>
     * 相似度 = |A ∩ B| / |A ∪ B|，对所有反向生成的问题取算术平均。
     *
     * @param generatedQuestions 反向生成的问题列表
     * @param originalQuestion   原问题
     * @return 平均 Jaccard 相似度；原问题为空 / 无有效生成问题时返回 {@code -1.0}
     */
    public static double answerRelevancyJaccard(List<String> generatedQuestions,
                                                String originalQuestion) {
        if (generatedQuestions == null || generatedQuestions.isEmpty()
                || originalQuestion == null || originalQuestion.isBlank()) {
            return UNAVAILABLE;
        }
        Set<String> originalTokens = tokenize(originalQuestion);
        if (originalTokens.isEmpty()) {
            return UNAVAILABLE;
        }
        double sum = 0.0;
        int count = 0;
        for (String question : generatedQuestions) {
            if (question == null || question.isBlank()) {
                continue;
            }
            Set<String> genTokens = tokenize(question);
            if (genTokens.isEmpty()) {
                continue;
            }
            sum += jaccard(genTokens, originalTokens);
            count++;
        }
        return count == 0 ? UNAVAILABLE : sum / count;
    }

    // ──────────────────────────────────────────────────────────────
    //  基础几何 / 集合运算（复用 + 单测）
    // ──────────────────────────────────────────────────────────────

    /**
     * 余弦相似度。
     *
     * @param a 向量 a
     * @param b 向量 b
     * @return 余弦相似度；任一向量为 null / 长度不一致 / 长度为零 / 存在零向量（范数为 0）时
     *         返回 {@link Double#NaN}（由调用方翻译为「不可用」）
     */
    public static double cosine(double[] a, double[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return Double.NaN;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return Double.NaN;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * Jaccard 相似度：|交集| / |并集|。
     *
     * @param a 集合 a
     * @param b 集合 b
     * @return 相似度；两个集合都为空时返回 {@code 1.0}
     */
    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        int intersection = 0;
        // 遍历较小的集合以降低复杂度
        Set<String> smaller = a.size() <= b.size() ? a : b;
        Set<String> larger = smaller == a ? b : a;
        for (String token : smaller) {
            if (larger.contains(token)) {
                intersection++;
            }
        }
        int union = a.size() + b.size() - intersection;
        return union == 0 ? 1.0 : (double) intersection / union;
    }

    /**
     * 轻量分词器（纯函数，口径与 {@link #answerRelevancyJaccard} 注释一致）。
     */
    static Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        String lower = text.toLowerCase(Locale.ROOT);

        StringBuilder asciiWord = new StringBuilder();
        List<String> cjkRun = new ArrayList<>();

        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (isCjk(c)) {
                flushAsciiWord(asciiWord, tokens);
                cjkRun.add(String.valueOf(c));
            } else if (Character.isLetterOrDigit(c) || c == '_') {
                flushCjkRun(cjkRun, tokens);
                asciiWord.append(c);
            } else {
                flushAsciiWord(asciiWord, tokens);
                flushCjkRun(cjkRun, tokens);
            }
        }
        flushAsciiWord(asciiWord, tokens);
        flushCjkRun(cjkRun, tokens);
        return tokens;
    }

    private static boolean isCjk(char c) {
        return (c >= CJK_BASE && c <= CJK_END)
                || (c >= CJK_EXT_A_BASE && c <= CJK_EXT_A_END)
                || (c >= CJK_COMPAT_BASE && c <= CJK_COMPAT_END);
    }

    private static void flushAsciiWord(StringBuilder buf, Set<String> tokens) {
        if (buf.length() > 0) {
            tokens.add(buf.toString());
            buf.setLength(0);
        }
    }

    private static void flushCjkRun(List<String> run, Set<String> tokens) {
        if (run.isEmpty()) {
            return;
        }
        tokens.addAll(run);
        for (int i = 0; i + 1 < run.size(); i++) {
            tokens.add(run.get(i) + run.get(i + 1));
        }
        run.clear();
    }
}
