package com.dis.fshipbot.service;

import com.dis.fshipbot.util.PromptDefaults;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Optional re-ranker + grounding judge backed by TypeSafe AI's Jev
 * (System One model: typed Noul judgments instead of generated text).
 *
 * Dark-shipped: both entry points are NO-OPs unless the corresponding provider
 * flag is "jev" AND a TYPESAFE_API_KEY is present. Every failure (no key,
 * timeout, 429, blocked egress, malformed response) falls back to the legacy
 * heuristic path — Jev must never fail a user query.
 *
 * Rerank design (mirrors TypeSafe's rerank cookbook): one Noul question per
 * candidate chunk, all evaluated in a SINGLE systemone call, then sort by noul.
 */
@Slf4j
@Service
public class JevRerankService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Instruction + criteria text is admin-editable (see PromptService). */
    @Autowired
    private PromptService promptService;

    /** Max chunk chars sent per rerank question (token budget guard). */
    private static final int MAX_CANDIDATE_CHARS = 1500;
    private static final int MAX_CONTEXT_CHARS = 14000;

    /**
     * Max answer chars sent to the grounding judge.
     *
     * <p>Was 2000, which truncated a typical 3-4k answer: the judge returned a
     * verdict on a fragment while the user was shown the whole thing. Two
     * problems — a fragment is a different, harder question to grade, and the
     * unjudged tail reached the user unchecked regardless of the verdict.
     * 8000 covers essentially every answer the model produces at
     * {@code rag.max-context-length=14000}, at a few thousand extra tokens.
     */
    private static final int MAX_ANSWER_CHARS = 8000;

    @Value("${rag.rerank-provider:none}")
    private String rerankProvider;

    @Value("${rag.grounding-provider:none}")
    private String groundingProvider;

    @Value("${jev.api-key:${TYPESAFE_API_KEY:}}")
    private String apiKey;

    @Value("${jev.model:jev-1.13.0}")
    private String model;

    @Value("${jev.base-url:https://api.typesafe.ai}")
    private String baseUrl;

    @Value("${jev.timeout-ms:8000}")
    private int timeoutMs;

    @Value("${jev.max-candidates:25}")
    private int maxCandidates;

    @Value("${jev.grounding-threshold:0.5}")
    private double groundingThreshold;

    /** Last grounding noul, per calling thread (ChatService is multi-threaded). */
    private final ThreadLocal<Double> lastGroundingNoul = new ThreadLocal<>();

    public double getLastGroundingNoul() {
        Double v = lastGroundingNoul.get();
        return v == null ? -1.0 : v;
    }

    @PostConstruct
    public void init() {
        if (isRerankEnabled() || isGroundingEnabled()) {
            log.info("Jev integration ON (rerank={}, grounding={}, model={})",
                    isRerankEnabled(), isGroundingEnabled(), model);
        } else {
            log.info("Jev integration OFF (rerank-provider={}, grounding-provider={}, key {})",
                    rerankProvider, groundingProvider,
                    apiKey == null || apiKey.isBlank() ? "absent" : "present");
        }
    }

    public boolean isRerankEnabled() {
        return "jev".equalsIgnoreCase(rerankProvider) && hasKey();
    }

    public boolean isGroundingEnabled() {
        // groundingProvider may be a cascade list ("jev,llm") — token match.
        if (!hasKey()) return false;
        for (String part : groundingProvider.toLowerCase(Locale.ROOT).split(",")) {
            if (part.trim().equals("jev")) return true;
        }
        return false;
    }

    private boolean hasKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Re-order candidates by Jev Noul relevance score. Returns the input list
     * untouched (same instance) whenever Jev is disabled or fails.
     */
    public List<EmbeddingMatch<TextSegment>> rerank(String query, List<EmbeddingMatch<TextSegment>> matches) {
        if (!isRerankEnabled() || matches == null || matches.size() <= 1) {
            return matches;
        }
        long start = System.currentTimeMillis();
        try {
            int n = Math.min(matches.size(), Math.max(1, maxCandidates));
            List<EmbeddingMatch<TextSegment>> head = new ArrayList<>(matches.subList(0, n));
            List<EmbeddingMatch<TextSegment>> tail = new ArrayList<>(matches.subList(n, matches.size()));

            ObjectNode questions = MAPPER.createObjectNode();
            for (int i = 0; i < head.size(); i++) {
                String chunk = head.get(i).embedded().text();
                if (chunk.length() > MAX_CANDIDATE_CHARS) {
                    chunk = chunk.substring(0, MAX_CANDIDATE_CHARS);
                }
                ObjectNode q = MAPPER.createObjectNode();
                q.put("type", "noul");
                q.put("instructions", promptService.render(
                        PromptDefaults.JEV_RERANK_INSTRUCTIONS,
                        Collections.singletonMap("chunk", chunk)));
                ObjectNode criteria = MAPPER.createObjectNode();
                criteria.put("true", promptService.get(PromptDefaults.JEV_RERANK_TRUE));
                criteria.put("false", promptService.get(PromptDefaults.JEV_RERANK_FALSE));
                q.set("criteria", criteria);
                questions.set("c" + i, q);
            }

            ObjectNode state = MAPPER.createObjectNode();
            state.put("question", query);

            ObjectNode body = MAPPER.createObjectNode();
            body.put("model", model);
            body.set("state", state);
            body.set("questions", questions);

            JsonNode answers = callSystemOne(body).path("answers");
            Map<EmbeddingMatch<TextSegment>, Double> noul = new HashMap<>();
            for (int i = 0; i < head.size(); i++) {
                double score = answers.path("c" + i).path("noul").asDouble(-1.0);
                noul.put(head.get(i), score);
            }

            List<EmbeddingMatch<TextSegment>> ranked = new ArrayList<>(head);
            // Stable sort: missing scores (-1.0) sink in original relative order.
            ranked.sort(new Comparator<EmbeddingMatch<TextSegment>>() {
                @Override
                public int compare(EmbeddingMatch<TextSegment> a, EmbeddingMatch<TextSegment> b) {
                    return Double.compare(noul.get(b), noul.get(a));
                }
            });
            ranked.addAll(tail);

            long ms = System.currentTimeMillis() - start;
            StringBuilder preview = new StringBuilder();
            for (int i = 0; i < Math.min(5, ranked.size()); i++) {
                if (i > 0) preview.append(", ");
                preview.append(String.format("%.2f", noul.getOrDefault(ranked.get(i), -1.0)));
            }
            log.info("Jev rerank: {} candidates in {}ms (top noul: {})", head.size(), ms, preview);
            return ranked;
        } catch (Exception e) {
            log.warn("Jev rerank failed, keeping heuristic order: {}", e.getMessage());
            return matches;
        }
    }

    /**
     * Noul verdict: does the context fully support the answer? Fail-open is the
     * CALLER's job (return value only); transport/parse errors propagate.
     */
    public boolean isGrounded(String context, String answer, String question) throws Exception {
        String ctx = context.length() > MAX_CONTEXT_CHARS
                ? context.substring(0, MAX_CONTEXT_CHARS) : context;
        String ans = answer.length() > MAX_ANSWER_CHARS
                ? answer.substring(0, MAX_ANSWER_CHARS) : answer;

        // Say so when the judge cannot see everything. A silent truncation makes
        // a PASS look like whole-answer validation when it was not.
        if (ans.length() < answer.length() || ctx.length() < context.length()) {
            log.warn("Grounding judge sees a truncated view: answer {}/{} chars, context {}/{} chars",
                    ans.length(), answer.length(), ctx.length(), context.length());
        }

        ObjectNode q = MAPPER.createObjectNode();
        q.put("type", "noul");
        q.put("instructions", promptService.render(
                PromptDefaults.JEV_GROUNDING_INSTRUCTIONS,
                Collections.singletonMap("answer", ans)));
        ObjectNode criteria = MAPPER.createObjectNode();
        criteria.put("true", promptService.get(PromptDefaults.JEV_GROUNDING_TRUE));
        criteria.put("false", promptService.get(PromptDefaults.JEV_GROUNDING_FALSE));
        q.set("criteria", criteria);

        ObjectNode state = MAPPER.createObjectNode();
        state.put("question", question);
        state.put("document_context", ctx);

        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.set("state", state);
        ObjectNode questions = MAPPER.createObjectNode();
        questions.set("grounded", q);
        body.set("questions", questions);

        long start = System.currentTimeMillis();
        JsonNode answers = callSystemOne(body).path("answers");
        double noul = answers.path("grounded").path("noul").asDouble(-1.0);
        lastGroundingNoul.set(noul);
        log.info("Jev grounding: noul={} (threshold={}) in {}ms",
                String.format("%.3f", noul), groundingThreshold, System.currentTimeMillis() - start);
        return noul >= groundingThreshold;
    }

    private JsonNode callSystemOne(ObjectNode body) throws Exception {
        String url = baseUrl.endsWith("/") ? baseUrl + "v1/systemone" : baseUrl + "/v1/systemone";
        byte[] payload = MAPPER.writeValueAsBytes(body);
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload);
        }
        int status = conn.getResponseCode();
        InputStream is = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
        byte[] resp = is != null ? is.readAllBytes() : new byte[0];
        conn.disconnect();
        if (status == 429) {
            throw new IllegalStateException("TypeSafe rate limit (429) — failing over to heuristics");
        }
        if (status < 200 || status >= 300) {
            String snippet = new String(resp, StandardCharsets.UTF_8);
            if (snippet.length() > 300) snippet = snippet.substring(0, 300);
            throw new IllegalStateException("TypeSafe HTTP " + status + ": " + snippet);
        }
        return MAPPER.readTree(resp);
    }
}
