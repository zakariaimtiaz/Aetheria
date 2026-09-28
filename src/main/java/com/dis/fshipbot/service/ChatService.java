package com.dis.fshipbot.service;

import com.dis.fshipbot.util.PromptDefaults;
import com.dis.fshipbot.model.ChatRequest;
import com.dis.fshipbot.model.ChatResponse;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import javax.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ChatService {

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private ProviderService providerService;

    @Autowired(required = false)
    private JevRerankService jevRerankService;

    @Autowired(required = false)
    private HybridFusionService hybridFusionService;

    @Autowired(required = false)
    private com.dis.fshipbot.repository.DocumentRepository documentRepository;

    @Autowired(required = false)
    private com.dis.fshipbot.repository.AppSettingRepository settingRepository;

    /**
     * All LLM prompts are DB-driven (admin-editable at runtime). The service falls
     * back to the compiled-in registry defaults if the DB is unreachable, so chat
     * never hard-depends on the prompt rows existing.
     */
    @Autowired
    private PromptService promptService;

    @Value("${rag.top-k:8}")
    private int topK;

    @Value("${rag.hybrid.enabled:true}")
    private boolean hybridEnabled;

    @Value("${rag.hybrid.vector-limit:100}")
    private int hybridVectorLimit;

    @Value("${rag.hybrid.lexical-limit:100}")
    private int hybridLexicalLimit;

    @Value("${rag.query-rewrite.enabled:true}")
    private boolean queryRewriteEnabled;

    @Value("${rag.similarity-threshold:0.55}")
    private double similarityThreshold;

    @Value("${rag.max-context-length:12000}")
    private int maxContextLength;

    @Value("${conversation.memory.size:8}")
    private int conversationMemorySize;

    @Value("${embedding.model.name:BGE Small EN v1.5}")
    private String embeddingModelName;

    private String getChatModelName() {
        String provider = providerService.getCurrentProvider();
        String model = providerService.getCurrentModelName();
        return provider.substring(0, 1).toUpperCase() + provider.substring(1) + " / " + model.trim();
    }

    // BGE-small-en-v1.5 is asymmetric: query embeddings MUST carry the
    // "Represent this sentence..." instruction, document embeddings MUST NOT.
    // The LangChain4j wrapper applies no prefix on either side (verified by
    // bytecode inspection), so we add it here — query-side ONLY. The shared
    // embeddingQuery string stays unprefixed: the lexical path, RRF fusion and
    // keyword heuristics all consume it and must not see these 4 noise words.
    private static final String BGE_QUERY_PREFIX =
            "Represent this sentence for searching relevant passages: ";

    private static final String DEGENERATE_FALLBACK =
            "My answer came out garbled. Please try rephrasing your question.";

    private static final String LEAK_FALLBACK =
            "I had trouble composing an answer. Please try rephrasing your question.";

    // Used only if an admin blanks the foul-language prompt; never reaches the LLM.
    private static final String FALLBACK_FOUL_REPLY =
            "It seems you might be upset. I'm here to help with work-related questions "
                    + "— how can I assist you?";

    @Value("${rag.query-expansion:false}")
    private boolean queryExpansionEnabled;

    // Show source badges under answers (References: ...). false hides them UI-wide.
    // DB-ONLY: read exclusively from app_setting key "app.references.enabled"
    // (category display), written by the Document Tools page toggle. There is
    // deliberately no application.properties fallback — the DB is the single
    // source of truth so the toggle takes effect without a redeploy/restart and
    // two places can never disagree. Missing row => references OFF.
    private static final String REFERENCES_KEY = "app.references.enabled";
    private static final boolean REFERENCES_DEFAULT = false;

    /**
     * Effective flag, read from the DB only. Defaults to {@link #REFERENCES_DEFAULT}
     * when the row is absent or the lookup fails (fail closed: no source badges).
     */
    public boolean isReferencesEnabled() {
        if (settingRepository == null) {
            return REFERENCES_DEFAULT;
        }
        try {
            String v = settingRepository.getValue(
                    REFERENCES_KEY, REFERENCES_DEFAULT ? "true" : "false");
            return !"false".equalsIgnoreCase(v.trim());
        } catch (Exception e) {
            log.warn("referencesEnabled DB lookup failed, defaulting to {}: {}",
                    REFERENCES_DEFAULT, e.getMessage());
            return REFERENCES_DEFAULT;
        }
    }

    // Single grounding switch: none | llm | jev | jev,llm (ordered cascade).
    @Value("${rag.grounding-provider:none}")
    private String groundingProvider;

    // Store conversation history by session ID
    private final Map<String, ConversationMemory> conversationMemoryMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("=".repeat(60));
        log.info("CHAT SERVICE INITIALIZED");
        log.info("   Provider: {}", providerService.getCurrentProvider());
        log.info("   Model: {}", getChatModelName());
        log.info("   Memory: {} messages", conversationMemorySize);
        log.info("   Query expansion: {}", queryExpansionEnabled ? "ON" : "OFF");
        log.info("   References: {}", isReferencesEnabled() ? "ON" : "OFF");
        log.info("   Grounding validation: {}", parseProviders(groundingProvider));
        log.info("=".repeat(60));
    }

    public ChatResponse answerQuestion(ChatRequest request) {
        Instant start = Instant.now();
        String sessionId = request.getSessionId() != null ? request.getSessionId() : "default";

        log.info("=".repeat(60));
        log.info("📝 Processing Question: '{}'", request.getQuestion());
        log.info("   Session: {}", sessionId);
        log.info("   Model: {}", getChatModelName());
        log.info("=".repeat(60));

        try {
            // Foul/slang guard FIRST — never send abuse to RAG or the LLM, and never
            // store it in conversation memory (it would ride along in every future prompt).
            if (containsFoulLanguage(request.getQuestion())) {
                log.info("🚫 Foul language detected - returning calm deflection");
                return buildResponse(request,
                        promptService.get(PromptDefaults.FOUL_LANGUAGE, FALLBACK_FOUL_REPLY),
                        Collections.emptyList(), start, sessionId, 0.0);
            }

            // Check if this is a casual conversation (no need for RAG)
            if (isCasualConversation(request.getQuestion())) {
                log.info("🗣️ Detected as casual conversation - skipping RAG");
                return handleCasualConversation(request, start, sessionId);
            }

            // Get or create conversation memory for this session
            ConversationMemory memory = conversationMemoryMap.computeIfAbsent(
                    sessionId, k -> new ConversationMemory(conversationMemorySize)
            );

            // 1. Build context-aware query for embedding (prevents embedding model from
            //    misinterpreting follow-ups like "What about the probation period?")
            List<ChatMessage> history = memory.getMessages();
            String embeddingQuery = buildEmbeddingQuery(request.getQuestion(), history);
            log.debug("Embedding query: {}", embeddingQuery);

            long tEmbedStart = System.currentTimeMillis();
            Embedding questionEmbedding = embeddingModel.embed(BGE_QUERY_PREFIX + embeddingQuery).content();
            long tEmbedMs = System.currentTimeMillis() - tEmbedStart;

            // 2. Find relevant documents — retrieve extra candidates for diversity
            // P0: fallback to a lower threshold when the strict threshold starves
            // tail chunks (paraphrased tail queries often score 0.40-0.50 on BGE-384).
            long tVecStart = System.currentTimeMillis();
            // P1: candidate pool decoupled from final topK — topK*10=200 cut off the
            // entitlement chunks (Annual 20d etc.) before any ranking ran (proven: they
            // were retrieved with the old 300-pool). 300-pool costs ~+50ms vector time,
            // LLM cost unchanged (final topK=20, ctx 14k).
            List<EmbeddingMatch<TextSegment>> rawMatches = embeddingStore.findRelevant(
                    questionEmbedding, topK * 15, similarityThreshold);
            if (rawMatches.size() < 5) {
                double fallbackThreshold = Math.max(0.35, similarityThreshold - 0.10);
                log.info("Low recall ({} hits @ {}), retrying @ {}",
                        rawMatches.size(), similarityThreshold, fallbackThreshold);
                List<EmbeddingMatch<TextSegment>> fallback = embeddingStore.findRelevant(
                        questionEmbedding, topK * 15, fallbackThreshold);
                if (fallback.size() > rawMatches.size()) {
                    rawMatches = fallback;
                }
            }
            long tVecMs = System.currentTimeMillis() - tVecStart;

            // Hybrid: lexical path (exact keywords / codes / amounts) fused with
            // vector path (meaning) via RRF. Lexical runs on noise-stripped text
            // so TOC/header lines can no longer outrank real sections.
            List<EmbeddingMatch<TextSegment>> lexicalMatches = Collections.emptyList();
            if (hybridEnabled && documentRepository != null && hybridFusionService != null) {
                long tLexStart = System.currentTimeMillis();
                try {
                    List<Map<String, Object>> rows = documentRepository.lexicalSearch(
                            embeddingQuery, hybridLexicalLimit);
                    lexicalMatches = toLexicalMatches(rows);
                    log.info("Lexical hits: {} ({} ms)", lexicalMatches.size(),
                            System.currentTimeMillis() - tLexStart);
                } catch (Exception e) {
                    log.warn("Lexical search failed, vector-only: {}", e.getMessage());
                }
                if (!lexicalMatches.isEmpty() || !rawMatches.isEmpty()) {
                    try {
                        List<EmbeddingMatch<TextSegment>> fused = hybridFusionService.fuse(
                                capList(rawMatches, hybridVectorLimit),
                                capList(lexicalMatches, hybridLexicalLimit),
                                isListQuestion(embeddingQuery));
                        if (!fused.isEmpty()) {
                            rawMatches = fused;
                        }
                    } catch (Exception e) {
                        log.warn("RRF fusion failed, vector-only: {}", e.getMessage());
                    }
                }
            }

            // 3a. Drop near-duplicate overlap chunks (same file, >85% trigram overlap).
            // Without this, sentence-overlap neighbours flood phrase-match slots and
            // crowd out entitlement chunks (e.g. Annual Leave 20 days).
            List<EmbeddingMatch<TextSegment>> deduplicated = deduplicateChunks(rawMatches);

            // 3b. Ensure source diversity — round-robin from multiple files
            List<EmbeddingMatch<TextSegment>> qualityMatches = ensureSourceDiversity(deduplicated, topK, embeddingQuery);

            // 3c. Optional Jev re-rank (dark by default: rag.rerank-provider=none).
            // Falls back to heuristic order on any failure — never breaks the query.
            long tRerankStart = System.currentTimeMillis();
            List<EmbeddingMatch<TextSegment>> rankedMatches = qualityMatches;
            if (jevRerankService != null) {
                rankedMatches = jevRerankService.rerank(embeddingQuery, qualityMatches);
            }
            long tRerankMs = System.currentTimeMillis() - tRerankStart;

            // 3. Build context from matches
            String context = "";
            List<ChatResponse.SourceInfo> sources = new ArrayList<>();

            if (!rankedMatches.isEmpty()) {
                log.info("Found {} relevant segments (best: {}, worst: {})",
                        rankedMatches.size(),
                        String.format("%.4f", rankedMatches.get(0).score()),
                        String.format("%.4f", rankedMatches.get(rankedMatches.size() - 1).score()));

                context = buildContext(rankedMatches);
                sources = buildSourceInfo(rankedMatches);
                log.info("Context length: {} chars", context.length());
            } else {
                log.info("No relevant documents found for: {}", request.getQuestion());
            }

            // 4. Prepare messages for the model
            List<ChatMessage> messages = new ArrayList<>();

            // Add system prompt with context (if available)
            String systemPrompt = context.isEmpty()
                    ? promptService.get(PromptDefaults.NO_CONTEXT)
                    : promptService.render(PromptDefaults.SYSTEM,
                            Collections.singletonMap("context", context));

            // Add conversation grounding note when history exists
            if (!history.isEmpty() && !context.isEmpty()) {
                systemPrompt += "\n" + promptService.get(PromptDefaults.CONVERSATION_GROUNDING);
            }

            messages.add(SystemMessage.from(systemPrompt));

            // Add conversation history (last N exchanges)
            messages.addAll(history);
            log.debug("Added {} history messages", history.size());

            // Add current question
            UserMessage currentQuestion = UserMessage.from(request.getQuestion());
            messages.add(currentQuestion);

            // 5. Get response from LLM
            log.debug("Calling model: {} with {} total messages", getChatModelName(), messages.size());
            long tLlmStart = System.currentTimeMillis();
            Response<AiMessage> response = generateWithRetry(messages, "main-answer");
            long tLlmMs = System.currentTimeMillis() - tLlmStart;
            String answer = cleanGeneratedAnswer(response.content().text());
            log.info("LLM main-answer finish={} usage={} chars={}",
                    response.finishReason(), response.tokenUsage(), answer.length());
            log.debug("Raw answer length: {} characters", answer.length());

            // Cut-off guard: a LENGTH stop (or an answer ending mid-word without
            // terminal punctuation) means the model was truncated — e.g. the
            // 2026-09-27 "Friendship Colours of the Ch" answer. Ask once to
            // continue from the cut point instead of showing a broken sentence.
            if (!answer.isEmpty()
                    && (response.finishReason() == FinishReason.LENGTH || isCutOff(answer))) {
                log.warn("Cut-off answer detected (finish={}, tail='{}') — requesting continuation",
                        response.finishReason(), tailOf(answer, 40));
                try {
                    List<ChatMessage> contMessages = new ArrayList<>(messages);
                    contMessages.add(AiMessage.from(answer));
                    contMessages.add(UserMessage.from(
                            "Your previous answer was cut off mid-sentence. "
                            + "Continue from exactly where you stopped, without repeating "
                            + "what you already said."));
                    long tContStart = System.currentTimeMillis();
                    Response<AiMessage> cont = generateWithRetry(contMessages, "main-answer-continue");
                    tLlmMs += System.currentTimeMillis() - tContStart;
                    String extra = cleanGeneratedAnswer(cont.content().text());
                    log.info("LLM continue finish={} usage={} chars={}",
                            cont.finishReason(), cont.tokenUsage(), extra.length());
                    if (!extra.isEmpty()) {
                        answer = answer + " " + extra;
                    }
                } catch (Exception e) {
                    log.warn("Continuation retry failed, keeping partial answer: {}", e.getMessage());
                }
            }

            // NIM sometimes returns 200 with empty content under load. An empty
            // answer reaches the UI as the literal "No answer received" fallback,
            // so retry once before giving up.
            if (answer.isEmpty()) {
                log.warn("Empty model answer — retrying once (main-answer)");
                long tRetryStart = System.currentTimeMillis();
                try {
                    Response<AiMessage> retry = generateWithRetry(messages, "main-answer-retry");
                    tLlmMs += System.currentTimeMillis() - tRetryStart;
                    answer = cleanGeneratedAnswer(retry.content().text());
                    log.debug("Retry answer length: {} characters", answer.length());
                } catch (Exception e) {
                    log.warn("Empty-answer retry failed: {}", e.getMessage());
                }
            }
            if (answer.isEmpty()) {
                log.warn("Model returned empty answer twice — sending friendly fallback");
                answer = "The AI model returned an empty response. Please try asking again in a moment.";
            }

            // Regeneration guard. Two failure shapes are caught here:
            //  - degeneration: a context flooded with same-section chunks sends the
            //    model into an enumeration loop ("[X] includes: ... [X] includes: ...").
            //  - reasoning leak: the model narrates its own deliberation instead of
            //    answering (2026-09-27 — asked to continue a list that was already
            //    complete, it emitted thousands of tokens of self-talk to the UI).
            // Regenerate once; a still-bad answer is never shown to the user.
            if (!answer.isEmpty()
                    && (isDegenerateAnswer(answer) || isReasoningLeak(answer))) {
                boolean leak = isReasoningLeak(answer);
                log.warn("{} answer detected (leak markers: {}) — regenerating once",
                        leak ? "Reasoning-leak" : "Degenerate",
                        leak ? findLeakMarkers(answer) : "n/a");
                try {
                    Response<AiMessage> regen = generateWithRetry(messages, "main-answer-degen-retry");
                    String second = cleanGeneratedAnswer(regen.content().text());
                    if (!second.isEmpty() && !isDegenerateAnswer(second) && !isReasoningLeak(second)) {
                        answer = second;
                    } else {
                        log.warn("Regenerated answer still bad — sending friendly fallback");
                        answer = leak ? LEAK_FALLBACK : DEGENERATE_FALLBACK;
                    }
                } catch (Exception e) {
                    log.warn("Regeneration retry failed: {}", e.getMessage());
                    answer = leak ? LEAK_FALLBACK : DEGENERATE_FALLBACK;
                }
            }
            boolean skipGrounding = answer.startsWith("The AI model returned an empty response.")
                    || answer.startsWith(DEGENERATE_FALLBACK)
                    || answer.startsWith(LEAK_FALLBACK);

            // 5b. Grounding validation — ordered cascade from rag.grounding-provider
            // (none | llm | jev | jev,llm). Short-circuits on first NOT-grounded so a
            // Jev reject never burns the expensive LLM call. Fail open throughout.
            // Skipped for empty answers (nothing to judge; saves a wasted Jev call).
            if (!skipGrounding && !answer.isEmpty() && !context.isEmpty() && !isNoInfoAnswer(answer)) {
                boolean grounded = true;
                String decidedBy = "none";
                List<String> validators = parseProviders(groundingProvider);
                for (String validator : validators) {
                    if ("jev".equals(validator) && jevRerankService != null
                            && jevRerankService.isGroundingEnabled()) {
                        try {
                            grounded = jevRerankService.isGrounded(context, answer, request.getQuestion());
                            decidedBy = "jev,noul=" + String.format("%.3f",
                                    jevRerankService.getLastGroundingNoul());
                        } catch (Exception e) {
                            log.warn("Jev grounding failed (allowing answer): {}", e.getMessage());
                            grounded = true;
                            decidedBy = "jev-error";
                            break;
                        }
                    } else if ("llm".equals(validator)) {
                        grounded = validateGrounding(context, answer, request.getQuestion());
                        decidedBy = "llm";
                    } else {
                        continue;
                    }
                    if (!grounded) break;
                }
                if (!validators.isEmpty()) {
                    log.debug("Grounding {} by {}", grounded ? "PASS" : "FAIL", decidedBy);
                }
                if (!grounded) {
                    log.warn("Grounding REJECTED by [{}] — replacing with safe fallback", decidedBy);
                    answer = "I found some potentially relevant documents, but I cannot confirm the answer is fully accurate based on them. Could you try rephrasing your question or asking about a specific aspect?";
                }
            }

            // 6. Update conversation memory
            memory.addMessage(currentQuestion);
            memory.addMessage(AiMessage.from(answer));
            log.debug("Conversation memory size for session {}: {}",
                    sessionId, memory.getMessages().size());

            // 7. Format answer
            String formattedAnswer = answer;
            if (!rankedMatches.isEmpty()) {
                formattedAnswer = formatAnswerWithSource(answer, sources);
            }

            Duration duration = Duration.between(start, Instant.now());
            log.info("✅ Answer generated in {} ms (embed={}ms vector={}ms rerank={}ms llm={}ms ctx={}chars n={})",
                    duration.toMillis(), tEmbedMs, tVecMs, tRerankMs, tLlmMs, context.length(), rankedMatches.size());

            // Calculate confidence from best similarity score
            double bestScore = rankedMatches.isEmpty() ? 0.0 : rankedMatches.get(0).score();

            // If no documents found OR model says no info, suppress sources and confidence
            if (rankedMatches.isEmpty() || isNoInfoAnswer(answer)) {
                sources = Collections.emptyList();
                bestScore = 0.0;
            }

            return buildResponse(request, formattedAnswer, sources, start, sessionId, bestScore);

        } catch (Exception e) {
            log.error("❌ Error processing question: {}", request.getQuestion(), e);
            String errorMsg;
            if (isQuotaExhausted(e)) {
                errorMsg = "The AI service has reached its usage limit. Please try again later.";
            } else if (isModelUnavailable(e)) {
                errorMsg = "The AI model is temporarily busy or unreachable. Please try again in a moment.";
            } else {
                errorMsg = "Sorry, an error occurred. Please try again.";
            }
            return ChatResponse.builder()
                    .question(request.getQuestion())
                    .answer(errorMsg)
                    .processingTimeMs(Duration.between(start, Instant.now()).toMillis())
                    .model(getChatModelName() + " + " + embeddingModelName)
                    .build();
        }
    }

    /**
     * Clear conversation memory for a session
     */
    public void clearConversation(String sessionId) {
        if (sessionId != null) {
            conversationMemoryMap.remove(sessionId);
            log.info("Cleared conversation memory for session: {}", sessionId);
        }
    }

    /**
     * Get conversation size for a session
     */
    public int getConversationSize(String sessionId) {
        ConversationMemory memory = conversationMemoryMap.get(sessionId);
        return memory != null ? memory.getMessages().size() : 0;
    }

    /**
     * Build response with proper source handling
     */
    private ChatResponse buildResponse(ChatRequest request, String answer,
                                       List<ChatResponse.SourceInfo> sources,
                                       Instant start, String sessionId, double bestScore) {
        String modelInfo = String.format("%s + %s", getChatModelName(), embeddingModelName);

        // Map similarity score to confidence: 0.65 -> 0.5, 0.85 -> 0.9, 1.0 -> 1.0
        // Below threshold gets 0.0 (no context found)
        Double confidence = null;
        if (bestScore > 0) {
            confidence = Math.min(1.0, Math.max(0.0, (bestScore - 0.5) / 0.5));
            confidence = Math.round(confidence * 100.0) / 100.0;
        }

        return ChatResponse.builder()
                .question(request.getQuestion())
                .answer(answer)
                .sources(request.isIncludeSources() && isReferencesEnabled() && !sources.isEmpty() ? sources : null)
                .processingTimeMs(Duration.between(start, Instant.now()).toMillis())
                .model(modelInfo)
                .sessionId(sessionId)
                .conversationSize(getConversationSize(sessionId))
                .confidence(confidence)
                .build();
    }
    private ChatResponse buildErrorResponse(ChatRequest request, Instant start) {
        return ChatResponse.builder()
                .question(request.getQuestion())
                .answer("Sorry, I encountered an error. Please try again.")
                .processingTimeMs(Duration.between(start, Instant.now()).toMillis())
                .model(getChatModelName() + " + " + embeddingModelName)
                .build();
    }
    /**
     * Remove near-duplicate chunks from the same file.
     * If two chunks from the same file share >70% text overlap, keep only the higher-scored one.
     */
    private List<EmbeddingMatch<TextSegment>> deduplicateChunks(List<EmbeddingMatch<TextSegment>> matches) {
        List<EmbeddingMatch<TextSegment>> result = new ArrayList<>();
        // Track seen text fingerprints per file to detect near-duplicates
        Map<String, Set<String>> seenPerFile = new HashMap<>();
        // Trigram sets cached per fingerprint (full-text comparison below)
        Map<String, Set<String>> trigramCache = new HashMap<>();

        for (EmbeddingMatch<TextSegment> match : matches) {
            String fileName = getMetadataValue(match.embedded().metadata(), "file_name", "unknown");
            String text = match.embedded().text().trim();
            // Full-text fingerprint: the old first-200-chars window only compared
            // the shared "[section]" header prefix of same-section chunks, so
            // boilerplate-heavy neighbours (general-rules blocks) never matched.
            String fingerprint = text;

            Set<String> seen = seenPerFile.computeIfAbsent(fileName, k -> new HashSet<>());
            Set<String> fpTrigrams = trigramCache.computeIfAbsent(fingerprint, this::getTrigrams);

            boolean isDuplicate = false;
            for (String existing : seen) {
                // 0.85: only true overlap-neighbours collapse; legitimately similar
                // sibling sections (Casual vs Sick Leave) stay. Input list is
                // score-ordered, so the kept chunk is always the higher-scored one.
                Set<String> exTrigrams = trigramCache.computeIfAbsent(existing, this::getTrigrams);
                if (jaccard(fpTrigrams, exTrigrams) > 0.85) {
                    isDuplicate = true;
                    break;
                }
            }

            if (!isDuplicate) {
                seen.add(fingerprint);
                result.add(match);
            }
        }

        int removed = matches.size() - result.size();
        if (removed > 0) {
            log.debug("Deduplication: removed {} near-duplicate chunks", removed);
        }
        return result;
    }

    /**
     * Simple text similarity using character trigram overlap.
     * Returns value between 0.0 (completely different) and 1.0 (identical).
     */
    private double textSimilarity(String a, String b) {
        if (a.equals(b)) return 1.0;
        if (a.length() < 10 || b.length() < 10) return a.equals(b) ? 1.0 : 0.0;
        return jaccard(getTrigrams(a), getTrigrams(b));
    }

    private double jaccard(Set<String> trigramsA, Set<String> trigramsB) {
        if (trigramsA.isEmpty() || trigramsB.isEmpty()) return 0.0;

        int intersection = 0;
        for (String t : trigramsA) {
            if (trigramsB.contains(t)) intersection++;
        }

        int union = trigramsA.size() + trigramsB.size() - intersection;
        return union > 0 ? (double) intersection / union : 0.0;
    }

    private Set<String> getTrigrams(String text) {
        String normalized = text.toLowerCase().replaceAll("\\s+", " ");
        Set<String> trigrams = new HashSet<>();
        for (int i = 0; i <= normalized.length() - 3; i++) {
            trigrams.add(normalized.substring(i, i + 3));
        }
        return trigrams;
    }

    /**
     * Ensure chunks from multiple files are represented.
     * Uses round-robin selection across files so one dominant file doesn't monopolize the context.
     * Returns up to {@code limit} chunks with preference for source diversity.
     */
    /**
     * Three-pass retrieval: keyword priority → file ranking → position-aware selection.
     *
     * Pass 0: Find chunks containing exact query keywords — these get top priority.
     * Pass 1: Rank remaining files by average similarity score.
     * Pass 2: Top files get chunks from beginning/middle/end (position-aware).
     * Final: Round-robin from other files for diversity.
     */
    private List<EmbeddingMatch<TextSegment>> ensureSourceDiversity(
            List<EmbeddingMatch<TextSegment>> matches, int limit, String query) {

        if (matches.size() <= limit) return matches;

        // Extract meaningful keywords from query (3+ chars, skip common words)
        Set<String> keywords = extractKeywords(query);
        log.info("Keyword priority: {}", keywords);

        // Multi-part questions ("X and Y?", "X? Y?"): keywords per sub-question so
        // the coverage repair below can guarantee every part gets chunks.
        List<String> subQuestions = splitSubQuestions(query);
        List<Set<String>> partKeywords = new ArrayList<>();
        for (String part : subQuestions) {
            partKeywords.add(extractKeywords(part));
        }
        if (subQuestions.size() > 1) {
            log.info("Sub-questions ({}): {}", subQuestions.size(), subQuestions);
        }

        // Pass 0: Find chunks containing exact keyword matches
        // Prioritize phrase (bigram) matches over single-word matches
        // Filter out TOC chunks first — they reference content but don't contain it
        List<EmbeddingMatch<TextSegment>> phraseMatches = new ArrayList<>();
        List<EmbeddingMatch<TextSegment>> keywordMatches = new ArrayList<>();
        List<EmbeddingMatch<TextSegment>> remaining = new ArrayList<>();
        int tocFiltered = 0;

        for (EmbeddingMatch<TextSegment> match : matches) {
            String text = match.embedded().text().toLowerCase();

            // Skip TOC/table-of-contents chunks (dots, page numbers, chapter refs only)
            // P0: drop entirely — TOC references content but contains no answers;
            // keeping it in `remaining` guaranteed its injection via the beginning-zone quota.
            if (isTocChunk(match.embedded().text())) {
                tocFiltered++;
                continue;
            }

            String stemmedText = stemText(text);
            // Check for phrase matches (bigrams containing spaces) — exact or stemmed
            boolean hasPhrase = keywords.stream()
                    .filter(k -> k.contains(" "))
                    .anyMatch(k -> text.contains(k) || stemmedText.contains(stemText(k)));

            // Check for single keyword matches — exact or stemmed (probationary==probation)
            boolean hasKeyword = keywords.stream()
                    .filter(k -> !k.contains(" "))
                    .anyMatch(k -> text.contains(k) || stemmedText.contains(stemText(k)));

            if (hasPhrase) {
                phraseMatches.add(match);
            } else if (hasKeyword) {
                keywordMatches.add(match);
            } else {
                remaining.add(match);
            }
        }

        // Phrase + keyword matches FIRST — they are the strongest signal,
        // but phrase matches are capped at half the limit: an exact bigram like
        // "leave policy" repeats across every general-rules overlap-neighbour and
        // would otherwise fill ALL slots, starving entitlement chunks
        // (Annual/Casual/Sick day counts). Overflow returns to `remaining`.
        // The per-section cap applies GLOBALLY (phrase + keyword + deferred +
        // positional passes share one counter) — otherwise Pass 2a/2b re-flood the
        // context with the capped section and the model loops over it.
        final int MAX_PER_SECTION = 5;
        Map<String, Integer> perSectionCount = new HashMap<>();
        int slotsUsed = 0;
        List<EmbeddingMatch<TextSegment>> selected = new ArrayList<>();

        // List questions ("what are the types...") need one chunk PER SECTION, not a
        // greedy top-N of one topic: cap the greedy Pass-0 fill at half the limit so
        // the deferred section round-robin + positional fill always get slots (proven
        // by rank trace: 20/20 greedy picks buried the 20d/10d/maternity entitlement
        // chunks at fused ranks 26-81 while off-topic rare-word stuffers won).
        boolean listQ = isListQuestion(query);
        int greedyLimit = listQ ? Math.max(4, limit / 2) : limit;

        // Add phrase matches (highest priority, capped)
        int phraseCap = Math.max(4, limit / 2);
        if (listQ) phraseCap = Math.max(2, limit / 4);
        List<EmbeddingMatch<TextSegment>> phraseTop =
                phraseMatches.subList(0, Math.min(phraseMatches.size(), phraseCap));
        int phraseTake = 0;
        for (EmbeddingMatch<TextSegment> m : phraseTop) {
            if (reserveSectionSlot(perSectionCount, MAX_PER_SECTION, m)) {
                selected.add(m);
                phraseTake++;
                slotsUsed++;
            } else {
                remaining.add(m);
            }
        }
        if (phraseMatches.size() > phraseTop.size()) {
            remaining.addAll(phraseMatches.subList(phraseTop.size(), phraseMatches.size()));
        }

        // Score keyword matches by rarity — rare keywords (like "gratuity") rank higher
        // than common ones (like "service" which appears everywhere).
        // P0: relative threshold — fixed <=10 demotes every term in 50-150 page docs.
        int rareLimit = Math.max(10, matches.size() / 10);
        Set<String> rareKeywords = keywords.stream()
                .filter(kw -> {
                    long count = matches.stream()
                            .filter(m -> {
                                String t = m.embedded().text().toLowerCase();
                                return t.contains(kw) || stemText(t).contains(stemText(kw));
                            })
                            .count();
                    return count <= rareLimit;
                })
                .collect(Collectors.toSet());

        // P1: order by rarity, then VECTOR SCORE (not density). Density favoured
        // long chunks stuffing common words (e.g. travel-allowance chunks beat the
        // Annual Leave chunk for "day allowances" queries). Score breaks ties by
        // semantic similarity first.
        List<EmbeddingMatch<TextSegment>> scoredKeywords = keywordMatches.stream()
                .sorted((a, b) -> {
                    int rareA = countKeywords(a.embedded().text().toLowerCase(), rareKeywords);
                    int rareB = countKeywords(b.embedded().text().toLowerCase(), rareKeywords);
                    if (rareB != rareA) return Integer.compare(rareB, rareA);
                    int scoreCmp = Double.compare(b.score(), a.score());
                    if (scoreCmp != 0) return scoreCmp;
                    // Final tie-break by total keyword density
                    int totalA = countKeywords(a.embedded().text().toLowerCase(), keywords);
                    int totalB = countKeywords(b.embedded().text().toLowerCase(), keywords);
                    return Integer.compare(totalB, totalA);
                })
                .collect(Collectors.toList());

        // Add keyword matches (up to limit) with the shared per-section cap so one
        // section (e.g. Casual/Sick Leave) cannot crowd out sibling sections
        // (Annual Leave). Overflow goes back to `remaining` for position-aware fill.
        Set<EmbeddingMatch<TextSegment>> selectedKeywords = new HashSet<>();
        for (EmbeddingMatch<TextSegment> m : scoredKeywords) {
            if (slotsUsed >= greedyLimit) break;
            if (!reserveSectionSlot(perSectionCount, MAX_PER_SECTION, m)) {
                continue; // section full — stays deferred for later passes
            }
            selected.add(m);
            selectedKeywords.add(m);
            slotsUsed++;
        }

        // Deferred keyword matches stay in their own bucket (scored order) — they are
        // query-relevant by definition and fill first in Pass 2a. Mixing them into the
        // generic `remaining` pool let positional picks drown them under high-score
        // general chunks (proven: 147 deferred, entitlement chunks got zero slots).
        List<EmbeddingMatch<TextSegment>> deferredKeywords = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> m : scoredKeywords) {
            if (!selectedKeywords.contains(m)) {
                deferredKeywords.add(m);
            }
        }

        // Multi-part coverage repair: if any sub-question has zero selected chunks,
        // force in its best-scoring match (evicting the lowest-priority keyword pick
        // when full). Without this, one dominant part eats all slots and the rest of
        // a compound question ("X and Y?") gets a refusal instead of a partial answer.
        if (partKeywords.size() > 1) {
            slotsUsed = ensurePartCoverage(selected, selectedKeywords, scoredKeywords,
                    partKeywords, rareKeywords, keywords, limit, slotsUsed);
        }

        int remainingSlots = limit - slotsUsed;

        // Identify files that had keyword matches — expand context from these files
        Set<String> keywordHitFiles = new LinkedHashSet<>();
        for (EmbeddingMatch<TextSegment> m : phraseMatches) {
            keywordHitFiles.add(getMetadataValue(m.embedded().metadata(), "file_name", "unknown"));
        }
        for (EmbeddingMatch<TextSegment> m : selected) {
            if (!phraseMatches.contains(m)) {
                keywordHitFiles.add(getMetadataValue(m.embedded().metadata(), "file_name", "unknown"));
            }
        }

        if (!phraseMatches.isEmpty() || !keywordMatches.isEmpty() || tocFiltered > 0) {
            log.info("Pass 0: {} phrase + {}/{} keyword, {} TOC filtered → {} selected (of {} limit)",
                    phraseTake, selected.size() - phraseTake, keywordMatches.size(),
                    tocFiltered, slotsUsed, limit);
            log.info("Keyword-hit files: {}", keywordHitFiles);
        }

        if (remainingSlots <= 0) {
            log.info("Selected {} chunks from keyword matches", selected.size());
            return selected;
        }

        // Pass 1: Group ALL remaining chunks by file (including from keyword-hit files)
        Map<String, List<EmbeddingMatch<TextSegment>>> byFile = remaining.stream()
                .collect(Collectors.groupingBy(
                        m -> getMetadataValue(m.embedded().metadata(), "file_name", "unknown"),
                        LinkedHashMap::new,
                        Collectors.toList()));

        int fileCount = byFile.size();

        Map<String, Double> fileScores = new LinkedHashMap<>();
        for (Map.Entry<String, List<EmbeddingMatch<TextSegment>>> entry : byFile.entrySet()) {
            double avgScore = entry.getValue().stream()
                    .mapToDouble(EmbeddingMatch::score)
                    .average().orElse(0.0);
            fileScores.put(entry.getKey(), avgScore);
        }

        // Sort: keyword-hit files FIRST, then by similarity score
        List<String> rankedFiles = fileScores.entrySet().stream()
                .sorted((a, b) -> {
                    boolean aHit = keywordHitFiles.contains(a.getKey());
                    boolean bHit = keywordHitFiles.contains(b.getKey());
                    if (aHit != bHit) return aHit ? -1 : 1; // keyword-hit files first
                    return Double.compare(b.getValue(), a.getValue());
                })
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        // Pass 2a: deferred keyword matches fill half the leftover slots, best score
        // first. They are query-relevant by definition (contain leave/day/etc.) — the
        // entitlement chunks (Annual 20d, Casual 10d, Sick 14d, Maternity 16w) live here
        // after the per-section cap defers them. Proven by 09:09 log: positional picks
        // alone chose general-rules + travel noise over them.
        int kwFill = Math.min(deferredKeywords.size(), (remainingSlots + 1) / 2);
        // List questions ("what are the types...") need one chunk PER SECTION, not the
        // top-N of one section: round-robin deferred matches across section headers so
        // Annual/Casual/Sick/Maternity/Floating entitlements all survive.
        List<EmbeddingMatch<TextSegment>> deferredByScore;
        if (isListQuestion(query) && kwFill > 1) {
            Map<String, List<EmbeddingMatch<TextSegment>>> bySection = deferredKeywords.stream()
                    .collect(Collectors.groupingBy(this::sectionKeyOf, LinkedHashMap::new, Collectors.toList()));
            List<String> sectionOrder = bySection.entrySet().stream()
                    .sorted((a, b) -> Double.compare(
                            b.getValue().stream().mapToDouble(EmbeddingMatch<TextSegment>::score).max().orElse(0.0),
                            a.getValue().stream().mapToDouble(EmbeddingMatch<TextSegment>::score).max().orElse(0.0)))
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
            for (List<EmbeddingMatch<TextSegment>> group : bySection.values()) {
                group.sort(Comparator.comparingDouble(EmbeddingMatch<TextSegment>::score).reversed());
            }
            deferredByScore = new ArrayList<>();
            Map<String, Integer> cursorBySection = new HashMap<>();
            while (deferredByScore.size() < kwFill) {
                boolean addedAny = false;
                for (String sec : sectionOrder) {
                    if (deferredByScore.size() >= kwFill) break;
                    List<EmbeddingMatch<TextSegment>> group = bySection.get(sec);
                    int idx = cursorBySection.getOrDefault(sec, 0);
                    while (idx < group.size()
                            && !reserveSectionSlot(perSectionCount, MAX_PER_SECTION, group.get(idx))) {
                        idx++;
                    }
                    cursorBySection.put(sec, idx);
                    if (idx < group.size()) {
                        deferredByScore.add(group.get(idx));
                        cursorBySection.put(sec, idx + 1);
                        addedAny = true;
                    }
                }
                if (!addedAny) break;
            }
        } else {
            deferredByScore = new ArrayList<>();
            List<EmbeddingMatch<TextSegment>> byScore = deferredKeywords.stream()
                    .sorted(Comparator.comparingDouble(EmbeddingMatch<TextSegment>::score).reversed())
                    .collect(Collectors.toList());
            for (EmbeddingMatch<TextSegment> m : byScore) {
                if (deferredByScore.size() >= kwFill) break;
                if (reserveSectionSlot(perSectionCount, MAX_PER_SECTION, m)) {
                    deferredByScore.add(m);
                }
            }
        }
        selected.addAll(deferredByScore);
        slotsUsed += deferredByScore.size();
        remainingSlots -= deferredByScore.size();
        Set<EmbeddingMatch<TextSegment>> deferredTaken = new HashSet<>(deferredByScore);

        // Pass 2b: Position-aware selection — FAIR-SHARE across priority files only.
        // Files with no keyword hits AND below-median average score (e.g. unrelated
        // handbook noise, cervical-cancer sections) skip fair-share and only compete
        // in round-robin leftovers — otherwise they steal guaranteed slots.
        double medianAvg = fileScores.values().stream()
                .sorted().skip(fileScores.size() / 2).findFirst().orElse(0.0);
        List<String> priorityFiles = rankedFiles.stream()
                .filter(f -> keywordHitFiles.contains(f)
                        || fileScores.getOrDefault(f, 0.0) >= medianAvg)
                .collect(Collectors.toList());
        if (priorityFiles.isEmpty()) priorityFiles = rankedFiles;

        Set<String> selectedFiles = new LinkedHashSet<>();
        Map<String, Set<EmbeddingMatch<TextSegment>>> pickedPerFile = new LinkedHashMap<>();

        for (String fileName : priorityFiles) {
            if (remainingSlots <= 0) break;
            List<EmbeddingMatch<TextSegment>> fileChunks = byFile.get(fileName);
            if (fileChunks == null || fileChunks.isEmpty()) continue;

            int filesLeft = priorityFiles.size() - selectedFiles.size();
            int fairShare = Math.max(2, (int) Math.ceil((double) remainingSlots / filesLeft));
            int chunksForFile = Math.min(Math.min(fileChunks.size(), fairShare), remainingSlots);

            List<EmbeddingMatch<TextSegment>> fileSelected =
                    selectByPosition(fileChunks, chunksForFile);

            int addedForFile = 0;
            for (EmbeddingMatch<TextSegment> m : fileSelected) {
                if (reserveSectionSlot(perSectionCount, MAX_PER_SECTION, m)) {
                    selected.add(m);
                    addedForFile++;
                }
            }
            pickedPerFile.put(fileName, new HashSet<>(fileSelected));
            remainingSlots -= addedForFile;
            selectedFiles.add(fileName);
        }

        // Round-robin leftovers: best unpicked chunk per file, ranked order, repeat.
        while (remainingSlots > 0) {
            boolean addedAny = false;
            for (String fileName : rankedFiles) {
                if (remainingSlots <= 0) break;
                List<EmbeddingMatch<TextSegment>> fileChunks = byFile.get(fileName);
                if (fileChunks == null) continue;
                Set<EmbeddingMatch<TextSegment>> picked =
                        pickedPerFile.computeIfAbsent(fileName, k -> new HashSet<>());
                EmbeddingMatch<TextSegment> next = fileChunks.stream()
                        .filter(m -> !picked.contains(m))
                        .sorted(Comparator.comparingDouble(EmbeddingMatch<TextSegment>::score).reversed())
                        .findFirst().orElse(null);
                if (next != null) {
                    picked.add(next);
                    if (reserveSectionSlot(perSectionCount, MAX_PER_SECTION, next)) {
                        selected.add(next);
                        remainingSlots--;
                        addedAny = true;
                    }
                }
            }
            if (!addedAny) break;
        }

        log.info("Retrieval: {} total — {} keyword (incl {} deferred-fill) + {} positional from {} files",
                selected.size(), slotsUsed, deferredByScore.size(),
                selected.size() - slotsUsed, selectedFiles.size());
        log.info("Keyword-hit files: {} → expanded with position-aware chunks", keywordHitFiles);
        log.info("All selected files: {}", selectedFiles);

        return selected;
    }

    /**
     * Extract meaningful keywords and phrases from query for exact matching.
     * Returns both individual keywords and 2-word phrases (bigrams).
     */
    private Set<String> extractKeywords(String query) {
        Set<String> stopWords = Set.of(
                "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
                "have", "has", "had", "do", "does", "did", "will", "would", "could",
                "should", "may", "might", "shall", "can", "need", "dare", "ought",
                "used", "to", "of", "in", "for", "on", "with", "at", "by", "from",
                "as", "into", "through", "during", "before", "after", "above", "below",
                "between", "out", "off", "over", "under", "again", "further", "then",
                "once", "here", "there", "when", "where", "why", "how", "all", "each",
                "every", "both", "few", "more", "most", "other", "some", "such", "no",
                "not", "only", "own", "same", "so", "than", "too", "very", "just",
                "about", "what", "which", "who", "whom", "this", "that", "these",
                "those", "and", "but", "or", "if", "because", "until", "while",
                "i", "me", "my", "we", "our", "you", "your", "he", "him", "his",
                "she", "her", "it", "its", "they", "them", "their", "friendship",
                "ie", "e.g"
        );

        String[] words = query.toLowerCase().replaceAll("[^a-z0-9\\s-]", "").split("\\s+");

        // Filter meaningful words (3+ chars, not stop word)
        List<String> meaningfulWords = Arrays.stream(words)
                .filter(w -> w.length() >= 3)
                .filter(w -> !stopWords.contains(w))
                .collect(Collectors.toList());

        Set<String> result = new LinkedHashSet<>(meaningfulWords);

        // Extract bigrams (2-word phrases) for important word pairs
        for (int i = 0; i < words.length - 1; i++) {
            String w1 = words[i].replaceAll("[^a-z0-9-]", "");
            String w2 = words[i + 1].replaceAll("[^a-z0-9-]", "");
            if (w1.length() >= 3 && w2.length() >= 3
                    && !stopWords.contains(w1) && !stopWords.contains(w2)) {
                result.add(w1 + " " + w2);
            }
        }

        return result;
    }

    /**
     * Select chunks from different positions in the document to ensure coverage.
     * P0 fix: picks the BEST-SCORING chunks per zone (old code took zone[0..N],
     * so end zone [66..99] returned 66,67,68 and never the true tail 95+).
     */
    private List<EmbeddingMatch<TextSegment>> selectByPosition(
            List<EmbeddingMatch<TextSegment>> chunks, int count) {

        if (chunks.size() <= count) {
            return chunks.stream()
                    .sorted(Comparator.comparingDouble(EmbeddingMatch<TextSegment>::score).reversed())
                    .collect(Collectors.toList());
        }

        // Sort by chunk_index to get true document order (safe parse)
        List<EmbeddingMatch<TextSegment>> sorted = chunks.stream()
                .sorted(Comparator.comparingInt(m -> safeChunkIndex(m.embedded().metadata())))
                .collect(Collectors.toList());

        List<EmbeddingMatch<TextSegment>> selected = new ArrayList<>();
        int total = sorted.size();

        // Divide into 3 zones: beginning, middle, end
        int zoneSize = Math.max(1, total / 3);
        List<EmbeddingMatch<TextSegment>> beginning = new ArrayList<>(sorted.subList(0, Math.min(zoneSize, total)));
        List<EmbeddingMatch<TextSegment>> middle = new ArrayList<>(sorted.subList(Math.min(zoneSize, total), Math.min(zoneSize * 2, total)));
        List<EmbeddingMatch<TextSegment>> end = new ArrayList<>(sorted.subList(Math.min(zoneSize * 2, total), total));

        // Allocate: 20% beginning, 30% middle, 50% end (tail holds unique policy content).
        // fromEnd is NOT bottom-clamped: max(1,...) minimums summed past `count` for
        // small counts (e.g. count=2 → 1+1+1=3, the stray 21st chunk in the 09:09 log).
        int fromBeginning = Math.min(count, Math.max(1, (int) (count * 0.20)));
        int fromMiddle = Math.min(count - fromBeginning, Math.max(count > 1 ? 1 : 0, (int) (count * 0.30)));
        int fromEnd = Math.max(0, count - fromBeginning - fromMiddle);

        // Best-scoring within each zone, not first-N positionally
        selected.addAll(topByScore(beginning, fromBeginning));
        selected.addAll(topByScore(middle, fromMiddle));
        selected.addAll(topByScore(end, fromEnd));

        // Fill any shortfall (small zones) with global best-scoring leftovers
        if (selected.size() < count) {
            Set<EmbeddingMatch<TextSegment>> seen = new HashSet<>(selected);
            List<EmbeddingMatch<TextSegment>> rest = chunks.stream()
                    .filter(m -> !seen.contains(m))
                    .sorted(Comparator.comparingDouble(EmbeddingMatch<TextSegment>::score).reversed())
                    .collect(Collectors.toList());
            for (EmbeddingMatch<TextSegment> m : rest) {
                if (selected.size() >= count) break;
                selected.add(m);
            }
        }

        // Hard contract: never return more than requested (callers budget slots).
        if (selected.size() > count) {
            selected = selected.stream()
                    .sorted(Comparator.comparingDouble(EmbeddingMatch<TextSegment>::score).reversed())
                    .limit(count)
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        return selected;
    }

    private int safeChunkIndex(Metadata metadata) {
        try {
            return Integer.parseInt(getMetadataValue(metadata, "chunk_index", "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private List<EmbeddingMatch<TextSegment>> topByScore(List<EmbeddingMatch<TextSegment>> zone, int n) {
        return zone.stream()
                .sorted(Comparator.comparingDouble(EmbeddingMatch<TextSegment>::score).reversed())
                .limit(Math.min(n, zone.size()))
                .collect(Collectors.toList());
    }

    /** Light stemmer for keyword matching: probationary/probation, gratuities/gratuity.
     * NOTE: only strip a single trailing "s" (allowances->allowance, types->type,
     * days->day). A blanket "es"-strip mangles words (allowances->"allowanc"). */
    private String stemWord(String w) {
        if (w.length() <= 4) return w;
        if (w.endsWith("ies") && w.length() > 5) return w.substring(0, w.length() - 3) + "y";
        if (w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && w.length() > 4)
            return w.substring(0, w.length() - 1);
        if (w.endsWith("ing") && w.length() > 7) return w.substring(0, w.length() - 3);
        if (w.endsWith("ed") && w.length() > 6) return w.substring(0, w.length() - 2);
        if (w.endsWith("ion") && w.length() > 7) return w.substring(0, w.length() - 3);
        if (w.endsWith("ary") && w.length() > 6) return w.substring(0, w.length() - 3);
        return w;
    }

    private String stemText(String text) {
        String[] parts = text.split("\\s+");
        StringBuilder sb = new StringBuilder(text.length());
        for (String p : parts) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(stemWord(p));
        }
        return sb.toString();
    }

    /**
     * Build context string from matches.
     * P0: lowest-score chunks truncated first (never mid-sentence), section headers
     * injected so the LLM can ground tail answers without filename leakage.
     */
    private String buildContext(List<EmbeddingMatch<TextSegment>> matches) {
        List<EmbeddingMatch<TextSegment>> byScore = matches.stream()
                .sorted(Comparator.comparingDouble(EmbeddingMatch<TextSegment>::score).reversed())
                .collect(Collectors.toList());
        StringBuilder ctx = new StringBuilder();
        int used = 0;

        for (EmbeddingMatch<TextSegment> match : byScore) {
            String body = match.embedded().text().trim();
            String section = getMetadataValue(match.embedded().metadata(), "section_header", "");
            String chunkIdx = getMetadataValue(match.embedded().metadata(), "chunk_index", "?");
            // Chunks now carry their own "[section]" prefix line (header injection at
            // ingest) — don't label them twice.
            String header = (section.isBlank() || body.startsWith("[" + section.strip() + "]"))
                    ? ""
                    : "--- [Section: " + section + " | chunk " + chunkIdx + "] ---\n";
            String text = header + body;

            int remaining = maxContextLength - used;
            if (remaining <= 100) break;
            boolean isTable = "table".equals(getMetadataValue(match.embedded().metadata(), "content_type", ""));
            if (text.length() > remaining) {
                if (isTable) {
                    // Never cut a table mid-row: related rows must travel together.
                    // Skip to the next fitting chunk instead of truncating.
                    continue;
                }
                // cut at sentence/line boundary, never mid-sentence
                String cut = text.substring(0, remaining);
                int lastStop = Math.max(cut.lastIndexOf(". "), Math.max(cut.lastIndexOf("\n"), cut.lastIndexOf("? ")));
                if (lastStop > remaining * 0.5) {
                    text = cut.substring(0, lastStop + 1);
                } else {
                    // No sentence boundary: back off to a word boundary at minimum.
                    // A mid-word fragment gets quoted half-finished by the model, so if
                    // not even a word fits, skip the chunk instead of poisoning context.
                    int lastSpace = cut.lastIndexOf(' ');
                    if (lastSpace > remaining * 0.5) text = cut.substring(0, lastSpace);
                    else continue;
                }
            }

            ctx.append(text).append("\n\n");
            used += text.length() + 2;
        }

        return ctx.toString().trim();
    }

    /**
     * Build source information from matches
     */
    private List<ChatResponse.SourceInfo> buildSourceInfo(List<EmbeddingMatch<TextSegment>> matches) {
        Map<String, ChatResponse.SourceInfo> uniqueSources = new LinkedHashMap<>();

        for (EmbeddingMatch<TextSegment> match : matches) {
            TextSegment segment = match.embedded();
            Metadata metadata = segment.metadata();

            String documentName = getMetadataValue(metadata, "file_name", "Unknown");

            if (!uniqueSources.containsKey(documentName) ||
                    uniqueSources.get(documentName).getSimilarity() < match.score()) {

                ChatResponse.SourceInfo source = ChatResponse.SourceInfo.builder()
                        .documentName(documentName)
                        .similarity(match.score())
                        .build();

                uniqueSources.put(documentName, source);
            }
        }

        return new ArrayList<>(uniqueSources.values());
    }

    /**
     * Check if the error indicates quota/rate-limit exhaustion (429).
     * These should NOT be retried — each retry burns more quota.
     */
    private boolean isQuotaExhausted(Exception e) {
        Throwable ex = e;
        while (ex != null) {
            String msg = ex.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("429") ||
                        lower.contains("resource_exhausted") ||
                        lower.contains("quota exceeded")) {
                    return true;
                }
            }
            ex = ex.getCause();
        }
        return false;
    }

    /**
     * Check if the error indicates the model API is temporarily unavailable.
     * Covers: 503 UNAVAILABLE, rate limits, service overload.
     */
    private boolean isModelUnavailable(Exception e) {
        Throwable ex = e;
        while (ex != null) {
            String msg = ex.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("unavailable") ||
                        lower.contains("503") ||
                        lower.contains("rate") ||
                        lower.contains("overloaded") ||
                        lower.contains("too many requests") ||
                        lower.contains("resource_exhausted")) {
                    return true;
                }
            }
            ex = ex.getCause();
        }
        return false;
    }

    /**
     * Check if the answer indicates the model doesn't have information.
     */
    private boolean isNoInfoAnswer(String answer) {
        String normalized = answer.toLowerCase().trim();
        return normalized.contains("don't have enough information") ||
                normalized.contains("do not have enough information") ||
                normalized.contains("cannot answer") ||
                normalized.contains("no information") ||
                normalized.contains("not found") ||
                normalized.contains("unable to find") ||
                normalized.contains("doesn't contain") ||
                normalized.contains("does not contain") ||
                normalized.contains("no relevant documents") ||
                normalized.contains("no documents found");
    }

    /**
     * Validate that an answer is grounded in the provided context.
     * Uses a lightweight LLM call to check if claims are supported.
     * Returns true if grounded, false if hallucination detected.
     */
    private boolean validateGrounding(String context, String answer, String question) {
        try {
            Map<String, String> tokens = new HashMap<>();
            tokens.put("context", context);
            tokens.put("answer", answer);
            tokens.put("question", question);
            String prompt = promptService.render(PromptDefaults.GROUNDING_VALIDATION, tokens);

            List<ChatMessage> validationMessages = List.of(
                    SystemMessage.from("You are a strict fact-checking validator. Respond with ONLY one word."),
                    UserMessage.from(prompt)
            );

            Response<AiMessage> validationResponse = generateWithRetry(validationMessages, "grounding-validation");
            String verdict = validationResponse.content().text().trim().toUpperCase();

            boolean grounded = verdict.contains("GROUNDED") && !verdict.contains("NOT_GROUNDED");
            log.debug("Grounding validation result: {} (raw: '{}')", grounded ? "PASS" : "FAIL", verdict);
            return grounded;

        } catch (Exception e) {
            log.warn("Grounding validation failed (allowing answer): {}", e.getMessage());
            return true; // Fail open — don't block answers if validation itself errors
        }
    }

    /**
     * Keep the visible answer free of source citations. Sources travel in the
     * structured ChatResponse.sources array (rendered as badges by index.html),
     * so no "Sources: [Source: ...]" suffix is appended to the answer text.
     */
    private String formatAnswerWithSource(String answer, List<ChatResponse.SourceInfo> sources) {
        return cleanGeneratedAnswer(answer);
    }

    /**
     * Heuristic cut-off check: a complete answer ends with terminal punctuation
     * (or a closing quote/bracket). Anything else on a long answer — typically a
     * trailing letter mid-word — means the model output was truncated.
     *
     * <p>A trailing list item is a deliberate ending, not a cut: it carries no
     * terminal punctuation, so the character test alone cannot tell a finished
     * list from a broken word. See {@link #endsAtListBoundary}.
     */
    private boolean isCutOff(String answer) {
        if (answer == null) return false;
        String t = answer.trim();
        if (t.length() < 40) return false;
        char last = t.charAt(t.length() - 1);
        if (".!?\"'’”)]}:;".indexOf(last) >= 0) return false;
        return !endsAtListBoundary(t);
    }

    /**
     * True when the answer's final two lines are both list items, i.e. the model
     * stopped on a list boundary rather than mid-token.
     *
     * <p>Observed 2026-09-28 — "What are Friendship's core values?" returned the
     * four values as a bulleted list ending "…- Hope" with finish=STOP. The
     * character-only check called that truncated, the pipeline asked the model to
     * continue an already-complete list, and the forced continuation rambled to
     * 921 chars of unsupported text. Grounding then rejected it (noul=0.090) and
     * the user got the safe fallback for a question the corpus answers outright.
     */
    private boolean endsAtListBoundary(String trimmed) {
        String last = null;
        String prev = null;
        for (String line : trimmed.split("\\R")) {
            if (line.isBlank()) continue;
            prev = last;
            last = line.strip();
        }
        return last != null && prev != null && isListItem(last) && isListItem(prev);
    }

    /** Markdown bullet, numbered, or lettered list item. */
    private static boolean isListItem(String line) {
        String s = line.stripLeading();
        if (s.startsWith("- ") || s.startsWith("* ") || s.startsWith("+ ")
                || s.startsWith("\u2022 ") || s.startsWith("\u2013 ") || s.startsWith("\u2014 ")) {
            return true;
        }
        return s.matches("\\d+[.)]\\s+\\S.*") || s.matches("(?i)[a-z][.)]\\s+\\S.*");
    }

    private String tailOf(String s, int n) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= n ? t : t.substring(t.length() - n);
    }

    /**
     * Markers that only ever appear when the model narrates its own reasoning
     * instead of answering. Any single hit is conclusive: none of these can occur
     * in a legitimate answer about NGO policy, leave, benefits or budgets.
     */
    private static final List<String> LEAK_STRONG = List.of(
            "the user is pointing out", "the user asks", "the user wants", "the user insists",
            "according to the instructions", "per the instructions", "the system requires",
            "the system says", "but the system", "the system likely expects",
            "i must follow", "i must not", "i must provide", "i need to follow",
            "in my last response", "my previous response", "looking back at the history",
            "the only compliant action", "the correct action is", "the safe path is",
            "since the user insists", "output only the final response",
            "wait! looking", "the prompt says", "as an ai");

    /**
     * Softer first-person deliberation markers. A legitimate answer may contain one
     * of these by accident ("I think the policy allows..."), so a single hit is not
     * enough — see {@link #isReasoningLeak}.
     */
    private static final List<String> LEAK_SOFT = List.of(
            "let me think", "let me check", "let me double-check", "let me verify",
            "let me scan", "let me re-read", "let me consider", "let me break",
            "wait,", "hmm,", "scanning again", "first, let me", "now, let me",
            "i need to", "i should", "i will check", "i'll check", "i think",
            "i believe", "i must", "i can verify", "let me analyze", "let me review");

    /**
     * Detects a reasoning leak: the model narrating its deliberation instead of
     * answering. Observed 2026-09-27 — asked to continue an already-complete list,
     * the model emitted thousands of tokens of self-talk ("Okay, the user is
     * pointing out...", "Let me double-check the context...") which reached the UI.
     *
     * <p>Neither the cut-off guard (output was long and ended in a period) nor the
     * repetition guard (verbose but not looping) catches this, so it needs its own
     * check. Deliberately conservative to avoid rejecting real answers: one strong
     * marker is conclusive, but soft markers only trip when several co-occur.
     *
     * @param markerList used by {@link #findLeakMarkers} for logging
     * @return true when the answer is reasoning rather than a response
     */
    private boolean isReasoningLeak(String answer) {
        return !findLeakMarkers(answer).isEmpty();
    }

    /** Returns the distinct leak markers present (empty when the answer is clean). */
    private List<String> findLeakMarkers(String answer) {
        if (answer == null || answer.length() < 80) {
            return Collections.emptyList();
        }
        String lower = answer.toLowerCase(Locale.ROOT);

        List<String> hits = new ArrayList<>();
        for (String m : LEAK_STRONG) {
            if (lower.contains(m)) {
                hits.add(m);
            }
        }
        if (!hits.isEmpty()) {
            return hits;
        }

        int softHits = 0;
        for (String m : LEAK_SOFT) {
            if (lower.contains(m)) {
                hits.add(m);
                softHits++;
            }
        }
        // Several soft markers on a long answer = deliberation. One or two on a
        // short answer is ordinary phrasing, so it is allowed.
        boolean leak = softHits >= 3 || (softHits >= 2 && answer.length() > 1500);
        return leak ? hits : Collections.emptyList();
    }

    /**
     * Detect model degeneration (repetition loops): the same long line 4+ times,
     * or the same long sentence 3+ times. A looping answer is retried once and
     * never shown to the user.
     */
    private boolean isDegenerateAnswer(String answer) {
        if (answer == null || answer.isBlank()) return false;
        Map<String, Integer> lineCount = new HashMap<>();
        for (String raw : answer.split("\\R")) {
            String line = raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (line.length() < 30) continue;
            int c = lineCount.getOrDefault(line, 0) + 1;
            if (c >= 4) return true;
            lineCount.put(line, c);
        }
        Map<String, Integer> sentCount = new HashMap<>();
        String flat = answer.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        for (String raw : flat.split("(?<=[.?!]) ")) {
            String s = raw.trim();
            if (s.split("\\s+").length < 8) continue;
            int c = sentCount.getOrDefault(s, 0) + 1;
            if (c >= 3) return true;
            sentCount.put(s, c);
        }
        return false;
    }

    /**
     * Remove prompt scaffolding if a model emits it despite instructions.
     */
    private String cleanGeneratedAnswer(String answer) {
        if (answer == null) {
            return "";
        }

        String cleaned = answer.trim();

        int answerLabelIndex = cleaned.toLowerCase(Locale.ROOT).lastIndexOf("answer:");
        if (answerLabelIndex >= 0) {
            cleaned = cleaned.substring(answerLabelIndex + "answer:".length()).trim();
        }

        cleaned = cleaned.replaceAll("(?is)^\\s*helpful analysis of context\\s*:.*?(?:\\R\\s*)?(?=answer\\s*:|$)", "").trim();
        cleaned = cleaned.replaceAll("(?is)^\\s*(analysis|final answer)\\s*:\\s*", "").trim();

        return cleaned;
    }

    /**
     * Build a context-aware query for embedding.
     * Prepends recent conversation turns so the embedding model understands
     * follow-up questions like "What about the probation period?" after
     * a question about leave policy.
     *
     * Uses LLM-based query expansion to add relevant names, synonyms, and
     * related terms — works automatically for any new documents without
     * manual mapping.
     */
    /** "List me all ..." / "what are the types ..." → cover one chunk per section. */
    private boolean isListQuestion(String query) {
        return query.toLowerCase().matches(".*\\b(types?|kinds?|lists?|all|various|each)\\b.*");
    }

    private String sectionKeyOf(EmbeddingMatch<TextSegment> m) {
        String section = getMetadataValue(m.embedded().metadata(), "section_header", "");
        if (section.isBlank()) return "__none__";
        return section.length() > 60 ? section.substring(0, 60) : section;
    }

    /**
     * Global per-section admission: every selection pass (phrase, keyword,
     * deferred-fill, positional) shares one counter, so a section capped in an
     * early pass cannot flood back in through a later one. Returns true and
     * consumes a slot when admitted. Coverage repair is the only caller that
     * may bypass this (answering every part outranks diversity).
     */
    private boolean reserveSectionSlot(Map<String, Integer> perSectionCount, int maxPerSection,
            EmbeddingMatch<TextSegment> m) {
        String key = sectionKeyOf(m);
        int used = perSectionCount.getOrDefault(key, 0);
        if (used >= maxPerSection) return false;
        perSectionCount.put(key, used + 1);
        return true;
    }

    /**
     * Split a compound question ("X and Y?", "X? Y?") into sub-questions so each
     * part can be guaranteed chunk coverage. Joins are only split when BOTH sides
     * look substantive (20+ chars, 3+ words) — "terms and conditions" stays whole.
     * Capped at 3 parts.
     */
    private List<String> splitSubQuestions(String query) {
        List<String> parts = new ArrayList<>();
        // Split on sentence-ending "?" first (keep the mark for intent detection).
        for (String piece : query.split("\\?")) {
            String trimmed = piece.trim();
            if (trimmed.isEmpty()) continue;
            List<String> split = splitOnJoinWord(trimmed, " and ");
            if (split == null) split = splitOnJoinWord(trimmed, " also ");
            if (split == null) split = splitOnJoinWord(trimmed, ";");
            if (split != null) {
                parts.addAll(split);
            } else {
                parts.add(trimmed);
            }
            if (parts.size() >= 3) break;
        }
        if (parts.isEmpty()) parts.add(query);
        return parts.size() > 3 ? parts.subList(0, 3) : parts;
    }

    private List<String> splitOnJoinWord(String text, String joiner) {
        int idx = text.toLowerCase(Locale.ROOT).indexOf(joiner);
        if (idx < 0) return null;
        String left = text.substring(0, idx).trim();
        String right = text.substring(idx + joiner.length()).trim();
        if (left.length() >= 20 && right.length() >= 20
                && left.split("\\s+").length >= 3 && right.split("\\s+").length >= 3) {
            List<String> out = new ArrayList<>();
            out.add(left);
            out.add(right);
            return out;
        }
        return null;
    }

    private boolean matchesAnyKeyword(EmbeddingMatch<TextSegment> m, Set<String> kws) {
        if (kws == null || kws.isEmpty()) return true; // vacuous parts need no cover
        String text = m.embedded().text().toLowerCase();
        String stemmed = stemText(text);
        for (String kw : kws) {
            if (text.contains(kw) || stemmed.contains(stemText(kw))) return true;
        }
        return false;
    }

    /**
     * Guarantee every sub-question part has at least one selected chunk: for each
     * uncovered part, force in its best-scoring candidate (scoredKeywords order),
     * evicting the lowest-priority keyword pick when at the limit. Eviction never
     * orphans another part. Returns the updated slotsUsed.
     */
    private int ensurePartCoverage(List<EmbeddingMatch<TextSegment>> selected,
            Set<EmbeddingMatch<TextSegment>> selectedKeywords,
            List<EmbeddingMatch<TextSegment>> scoredKeywords,
            List<Set<String>> partKeywords, Set<String> rareKeywords, Set<String> keywords,
            int limit, int slotsUsed) {
        for (Set<String> part : partKeywords) {
            if (part == null || part.isEmpty()) continue;
            boolean covered = false;
            for (EmbeddingMatch<TextSegment> m : selected) {
                if (matchesAnyKeyword(m, part)) {
                    covered = true;
                    break;
                }
            }
            if (covered) continue;

            EmbeddingMatch<TextSegment> best = null;
            for (EmbeddingMatch<TextSegment> m : scoredKeywords) {
                if (!selected.contains(m) && matchesAnyKeyword(m, part)) {
                    best = m;
                    break;
                }
            }
            if (best == null) {
                log.info("Coverage repair: no candidate covers part {}", part);
                continue;
            }
            if (slotsUsed >= limit) {
                // Evict the lowest-priority keyword pick that orphans no part.
                List<EmbeddingMatch<TextSegment>> picks = new ArrayList<>(selectedKeywords);
                picks.sort((a, b) -> {
                    int rareA = countKeywords(a.embedded().text().toLowerCase(), rareKeywords);
                    int rareB = countKeywords(b.embedded().text().toLowerCase(), rareKeywords);
                    if (rareA != rareB) return Integer.compare(rareA, rareB);
                    return Double.compare(a.score(), b.score());
                });
                boolean evicted = false;
                for (EmbeddingMatch<TextSegment> victim : picks) {
                    selected.remove(victim);
                    boolean orphans = false;
                    for (Set<String> other : partKeywords) {
                        if (other == null || other.isEmpty() || other == part) continue;
                        boolean stillCovered = false;
                        for (EmbeddingMatch<TextSegment> m : selected) {
                            if (matchesAnyKeyword(m, other)) {
                                stillCovered = true;
                                break;
                            }
                        }
                        if (!stillCovered) {
                            orphans = true;
                            break;
                        }
                    }
                    if (!orphans) {
                        selectedKeywords.remove(victim);
                        slotsUsed--;
                        evicted = true;
                        break;
                    }
                    selected.add(victim); // restore: would orphan another part
                }
                if (!evicted) {
                    log.info("Coverage repair: no safe eviction for part {}", part);
                    continue;
                }
            }
            selected.add(best);
            selectedKeywords.add(best);
            slotsUsed++;
            log.info("Coverage repair: forced chunk for part {} (score {})", part,
                    String.format("%.4f", best.score()));
        }
        return slotsUsed;
    }

    private List<EmbeddingMatch<TextSegment>> capList(List<EmbeddingMatch<TextSegment>> in, int limit) {
        if (in == null || in.size() <= limit || limit <= 0) return in == null ? Collections.emptyList() : in;
        return new ArrayList<>(in.subList(0, limit));
    }

    private List<EmbeddingMatch<TextSegment>> toLexicalMatches(List<Map<String, Object>> rows) {
        List<EmbeddingMatch<TextSegment>> out = new ArrayList<>();
        if (rows == null) return out;
        int rank = 0;
        for (Map<String, Object> row : rows) {
            try {
                String text = String.valueOf(row.getOrDefault("text", ""));
                if (text == null || text.isBlank()) continue;
                Metadata meta = new Metadata();
                if (row.get("file_name") != null) meta.put("file_name", String.valueOf(row.get("file_name")));
                // metadata jsonb -> propagate section_header / content_type / chunk_index when present
                Object metaObj = row.get("metadata");
                if (metaObj != null) {
                    String js = String.valueOf(metaObj);
                    String section = extractJsonString(js, "section_header");
                    String ctype = extractJsonString(js, "content_type");
                    String cidx = extractJsonString(js, "chunk_index");
                    if (section != null) meta.put("section_header", section);
                    if (ctype != null) meta.put("content_type", ctype);
                    if (cidx != null) meta.put("chunk_index", cidx);
                }
                TextSegment seg = TextSegment.from(text, meta);
                // Pseudo-score from lexical rank (best first -> 0.99 descending).
                double score = Math.max(0.40, 0.99 - rank * 0.01);
                Object idObj = row.get("embedding_id");
                String id = idObj != null ? String.valueOf(idObj) : null;
                out.add(new EmbeddingMatch<>(score, id, null, seg));
                rank++;
            } catch (Exception e) {
                log.debug("Skipping lexical row: {}", e.getMessage());
            }
        }
        return out;
    }

    private String extractJsonString(String json, String key) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]{1,200})\"")
                    .matcher(json);
            return m.find() ? m.group(1) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * History-aware rewrite for follow-ups (zero extra LLM quota).
     * "What about probation?" after leave discussion -> "probation period leave policy?"
     * Only fires on short follow-ups; full questions pass through untouched so the
     * embedding is never contaminated (see buildEmbeddingQuery).
     */
    private String rewriteFollowUp(String question, List<ChatMessage> history) {
        if (!queryRewriteEnabled || history == null || history.isEmpty()) return question;
        String q = question.strip();
        if (q.length() > 80) return question;
        String lower = q.toLowerCase(Locale.ROOT);
        boolean looksFollowUp = lower.matches("^(what about|how about|and what|and how|what if|how long|how many|how much|why|when|where|which|who|does it|is it|are they|do they).*")
                || lower.matches("^(it|they|that|this|those|these)\\b.*")
                || (!lower.contains("?") && q.split("\\s+").length <= 6);
        if (!looksFollowUp) return question;
        // Last user message carries the topic ("leave policy...").
        String lastUser = null;
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage m = history.get(i);
            if (m instanceof UserMessage) {
                lastUser = ((UserMessage) m).singleText();
                break;
            }
        }
        if (lastUser == null || lastUser.isBlank()) return question;
        // Carry 3-6 salient keywords (>=4 chars, not stop-words) from the last question.
        Set<String> stop = Set.of("what", "when", "where", "which", "does", "have", "with", "from", "about", "policy", "friendship");
        StringBuilder carry = new StringBuilder();
        int added = 0;
        for (String tok : lastUser.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\s-]", " ").split("\\s+")) {
            if (tok.length() >= 4 && !stop.contains(tok) && !lower.contains(tok) && !carry.toString().contains(tok)) {
                if (added > 0) carry.append(" ");
                carry.append(tok);
                if (++added >= 5) break;
            }
        }
        if (added == 0) return question;
        String rewritten = q + " " + carry;
        log.info("Follow-up rewrite: '{}' -> '{}'", question, rewritten);
        return rewritten;
    }

    private String buildEmbeddingQuery(String question, List<ChatMessage> history) {
        String rewritten = rewriteFollowUp(question, history);

        // CRITICAL: Embedding query must be the CURRENT question only.
        // Including conversation history contaminates retrieval — e.g. asking "leave policy"
        // after "laptop policy" would retrieve laptop chunks because the history includes
        // "Friendship laptop policy" which dominates the embedding similarity.
        // The LLM still gets full history for context — only embedding is affected.
        //
        // NOTE: no static synonym map here by design. Hardcoded term lists rot as soon
        // as new documents arrive with new vocabulary. Dynamic expansion lives in
        // expandQueryForEmbedding (LLM, opt-in via rag.query-expansion); otherwise the
        // raw question plus the hybrid lexical path carry the query.
        return expandQueryForEmbedding(rewritten);
    }

    /**
     * Expand a user question with relevant names, synonyms, and related terms
     * using a lightweight LLM call. This helps the embedding model match document
     * chunks that use different terminology.
     *
     * Example: "Who is the founder of Friendship?" →
     *          "Who is the founder of Friendship? Runa Khan Friendship NGO founder executive director"
     *
     * Works automatically for any new documents — no manual mapping needed.
     * Falls back to original question if LLM call fails.
     * Skips expansion for short/simple questions to save API quota.
     */
    private String expandQueryForEmbedding(String question) {
        if (!queryExpansionEnabled) {
            return question;
        }

        // Skip expansion for short questions (likely simple enough for direct embedding)
        if (question.length() < 30) {
            log.debug("Query expansion skipped (question too short)");
            return question;
        }

        try {
            String prompt = promptService.render(PromptDefaults.QUERY_EXPANSION,
                    Collections.singletonMap("question", question));

            List<ChatMessage> messages = List.of(
                    SystemMessage.from(promptService.get(PromptDefaults.QUERY_EXPANSION_SYSTEM)),
                    UserMessage.from(prompt)
            );

            Response<AiMessage> response = generateWithRetry(messages, "query-expansion");
            String expanded = response.content().text().trim();

            // Safety: if expansion is too long or empty, use original
            if (expanded.isEmpty() || expanded.length() > 200) {
                log.debug("Query expansion skipped (result too long/empty), using original");
                return question;
            }

            log.info("Query expansion: '{}' → '{}'", question, expanded);
            return expanded;

        } catch (Exception e) {
            log.warn("Query expansion failed, using original question: {}", e.getMessage());
            return question;
        }
    }

    /**
     * Safely get metadata value
     */
    private String getMetadataValue(Metadata metadata, String key, String defaultValue) {
        String value = metadata.get(key);
        return value != null ? value : defaultValue;
    }

    /**
     * Generate a response with retry on transient errors only.
     * Does NOT retry on quota/rate-limit (429) — each retry burns quota and makes it worse.
     * Instead, throws immediately so the caller returns a user-friendly "try again" message.
     */
    private Response<AiMessage> generateWithRetry(List<ChatMessage> messages, String label) {
        int maxRetries = 2;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return providerService.getCurrentModel().generate(messages);
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "";
                String lower = msg.toLowerCase();

                // Quota/rate-limit: fail fast — do NOT retry (each retry burns quota)
                boolean isQuotaError = lower.contains("429") ||
                        lower.contains("resource_exhausted") ||
                        lower.contains("quota exceeded");

                if (isQuotaError) {
                    log.warn("⚠️ Quota exhausted on {} — not retrying (would make it worse)", label);
                    throw e;
                }

                // Transient errors (503, overloaded, timeouts): retry with short backoff.
                // NOTE: client timeouts (InterruptedIOException: timeout, canceled)
                // MUST be retried — the first NIM attempt in the 2026-09-27 log timed
                // out and only a retry produced an answer. Previously only 503-style
                // messages matched, so timeouts escaped immediately.
                boolean isTransient = lower.contains("503") ||
                        lower.contains("unavailable") ||
                        lower.contains("overloaded") ||
                        lower.contains("temporarily") ||
                        lower.contains("timeout") ||
                        lower.contains("timed out") ||
                        lower.contains("interruptedioexception") ||
                        lower.contains("canceled") ||
                        lower.contains("cancelled") ||
                        lower.contains("connection reset") ||
                        lower.contains("connection refused") ||
                        lower.contains("broken pipe") ||
                        lower.contains("eofexception") ||
                        lower.contains("stream reset") ||
                        lower.contains("http/2 stream was reset");

                if (isTransient && attempt < maxRetries) {
                    long delayMs = 2000L * (attempt + 1); // 2s, 4s
                    log.warn("⚠️ Transient error on {} (attempt {}/{}), retrying in {}s...",
                            label, attempt + 1, maxRetries + 1, delayMs / 1000);
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                } else {
                    throw e;
                }
            }
        }
        throw new RuntimeException("Retry exhausted for " + label);
    }

    /**
     * Handle casual conversation without RAG
     */
    private ChatResponse handleCasualConversation(ChatRequest request, Instant start, String sessionId) {
        List<ChatMessage> messages = new ArrayList<>();

        // Use a simpler system prompt for casual chat
        String casualPrompt = promptService.get(PromptDefaults.CASUAL);

        messages.add(SystemMessage.from(casualPrompt));

        // Add conversation memory if available
        ConversationMemory memory = conversationMemoryMap.get(sessionId);
        if (memory != null) {
            messages.addAll(memory.getMessages());
        }

        UserMessage currentQuestion = UserMessage.from(request.getQuestion());
        messages.add(currentQuestion);

        try {
            Response<AiMessage> response = generateWithRetry(messages, "casual-chat");
            String answer = response.content().text();

            // Update memory
            if (memory == null) {
                memory = new ConversationMemory(conversationMemorySize);
                conversationMemoryMap.put(sessionId, memory);
            }
            memory.addMessage(currentQuestion);
            memory.addMessage(AiMessage.from(answer));

            return buildResponse(request, answer, Collections.emptyList(), start, sessionId, 0.0);

        } catch (Exception e) {
            log.error("Error in casual conversation", e);
            String errorMsg = isModelUnavailable(e)
                    ? "The AI model is temporarily busy or unreachable. Please try again in a moment."
                    : "Sorry, I encountered an error. Please try again.";
            return ChatResponse.builder()
                    .question(request.getQuestion())
                    .answer(errorMsg)
                    .processingTimeMs(Duration.between(start, Instant.now()).toMillis())
                    .model(getChatModelName() + " + " + embeddingModelName)
                    .build();
        }
    }
    /**
     * Foul/slang lexicon (word-boundary matched after normalization, so "hello"
     * never trips "hell"). Extend here — e.g. Bangla transliterations — as needed.
     */
    private static final Set<String> FOUL_WORDS = Set.of(
            "fuck", "fucking", "fucker", "fuckers", "motherfucker", "motherfuckers",
            "shit", "shits", "shitty", "bullshit",
            "bitch", "bitches", "bastard", "bastards",
            "asshole", "assholes", "arsehole", "dickhead", "dickheads",
            "dick", "dicks", "cock", "cocks", "pussy", "cunt",
            "whore", "whores", "slut", "sluts", "slutty",
            "faggot", "faggots", "retard", "retarded",
            "idiot", "idiots", "idiotic", "stupid", "dumb", "moron", "morons",
            "imbecile", "useless", "crap", "crappy", "suck", "sucks", "sucky",
            "damn", "goddamn", "hell", "shut up", "kill you");

    private static final Pattern FOUL_PATTERN = Pattern.compile(
            "\\b(" + String.join("|", FOUL_WORDS).replace(" ", "\\s+") + ")\\b");

    /**
     * Normalize user text for foul-language matching: lowercase, leetspeak
     * (sh1t, f*ck→fck won't match — masked forms need explicit variants),
     * collapse elongated letters (fuuuck→fuck). Masked forms like f**k are
     * intentionally NOT matched in v1 to avoid false positives.
     */
    private String normalizeForFoulCheck(String text) {
        String n = text.toLowerCase()
                .replace('0', 'o').replace('1', 'i').replace('3', 'e')
                .replace('4', 'a').replace('5', 's').replace('7', 't')
                .replace('@', 'a').replace('$', 's').replace('+', 't');
        n = n.replaceAll("[^a-z0-9 ]", " ");
        n = n.replaceAll("(.)\\1{2,}", "$1");
        return n;
    }

    private boolean containsFoulLanguage(String question) {
        if (question == null || question.isBlank()) return false;
        return FOUL_PATTERN.matcher(normalizeForFoulCheck(question)).find();
    }

    /**
     * Detect if question is casual conversation vs work-related
     */
    private boolean isCasualConversation(String question) {
        String lowerQuestion = question.toLowerCase().trim();

        // Check if question contains NGO/HR/work keywords — if so, always route to RAG
        String[] workKeywords = {"policy", "leave", "salary", "employee", "staff", "report",
                "procedure", "benefit", "training", "health", "safety", "office", "budget",
                "project", "program", "grant", "donor", "fund", "compliance", "audit",
                "attendance", "probation", "contract", "performance", "review", "meeting",
                "department", "team", "manager", "director", "coordinator", "volunteer",
                "field", "office", "headquarters", "branch", "region", "district",
                "founder", "ceo", "chairman", "president", "leader", "biography", "biography",
                "awards", "achievements", "recognition", "honors", "values", "mission",
                "vision", "history", "about", "who is", "tell me about",
                "chapter", "section", "article", "clause", "annex", "appendix", "handbook", "manual", "sop",
                "gratuity", "provident", "pension", "allowance", "overtime", "appraisal", "disciplinary",
                "grievance", "maternity", "paternity", "increment", "bonus", "insurance", "resign",
                "terminat", "retire", "ch.", "what is", "what are", "how much", "how many", "how long"};
        for (String kw : workKeywords) {
            if (lowerQuestion.contains(kw)) {
                return false; // Contains work keywords — route to RAG
            }
        }

        // Any substantive question (long or with ?) is never casual — except identity Qs handled below.
        if (lowerQuestion.contains("?") && lowerQuestion.length() > 30) {
            return false;
        }

        // Pure greetings only: short, no question mark, no substantive content.
        // P0 fix: "Hi, what is gratuity in Ch 26?" used to bypass RAG ("gratuity"
        // not in workKeywords). Long/?-questions always go to RAG.
        if (lowerQuestion.length() < 40 && !lowerQuestion.contains("?")
                && lowerQuestion.matches("^(hello|hi|hey|greetings|good\\s*(morning|afternoon|evening))[\\s,!\\.]*.*$")) {
            // still require no substantive words beyond the greeting itself
            String withoutGreeting = lowerQuestion.replaceAll(
                    "^(hello|hi|hey|greetings|good\\s*(morning|afternoon|evening))[\\s,!\\.]*", "").trim();
            if (withoutGreeting.isEmpty() || withoutGreeting.split("\\s+").length <= 3) {
                return true;
            }
        }

        // How are you / wellbeing (only if short and no work keywords)
        if (lowerQuestion.matches(".*how\\s+(are|'re|is)\\s+(you|everyone|the\\s+team|things).*$") ||
                lowerQuestion.matches(".*(how'?s|how is)\\s+(it\\s+going|your\\s+day|the\\s+day).*$")) {
            return true;
        }

        // Weather (only if short and no work keywords — "weather policy" should go to RAG)
        if (lowerQuestion.length() < 30 &&
                (lowerQuestion.contains("weather") || lowerQuestion.contains("rain") ||
                 lowerQuestion.contains("sunny") || lowerQuestion.contains("temperature"))) {
            return true;
        }

        // Office atmosphere (only if specifically about atmosphere, not office-related topics)
        if (lowerQuestion.matches(".*(office\\s+atmosphere|office\\s+busy|office\\s+quiet|office\\s+vibe).*$")) {
            return true;
        }

        // Thank you / politeness
        if (lowerQuestion.matches("^(thanks|thank you|appreciate it|goodbye|bye).*$")) {
            return true;
        }

        // Questions about the assistant itself
        if (lowerQuestion.matches(".*(what'?s?|tell me) your (name|purpose|role|capabilities).*$") ||
                lowerQuestion.matches(".*(what can you|how can you) help.*$") ||
                lowerQuestion.matches(".*who are you.*$")) {
            return true;
        }

        return false;
    }

    /**
     * Detect if a chunk is a table-of-contents entry.
     * TOC entries have leaders (dots/periods), page numbers, and chapter/section references
     * but don't contain substantive content.
     */
    private static final Pattern REPEAT_BLOCK =
            Pattern.compile("(.{20,}?)\\1{3,}", Pattern.DOTALL);

    private boolean isTocChunk(String text) {
        if (text == null || text.isBlank()) return false;
        // P1: garbled index chunks — the Handbook leave-index chunk repeats
        // "Leave Policy <U+FFFD-box-chars>" ~60x, eating 1k context chars with zero
        // information. Kill on encoding-mangled chars or 4+ repeats of a 20+ char block.
        // Normal prose never repeats a 20-char block 4x consecutively.
        int mangled = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '�') mangled++;
        }
        if (mangled >= 3) return true;
        if (REPEAT_BLOCK.matcher(text).find()) return true;
        // P0: line-based detection — old version anchored to whole-chunk end, so a
        // 1000-char chunk of "TOC + body" never flagged. Flag when TOC lines dominate.
        String[] lines = text.split("\\n");
        int tocLines = 0;
        int contentLines = 0;
        for (String line : lines) {
            String t = line.strip();
            if (t.isEmpty()) continue;
            if (t.matches("(?i)^#{0,4}\\s*(table of contents|contents)\\s*$")) return true;
            boolean toc = t.matches(".*\\.{2,}\\s*\\d+\\s*$")
                    || t.matches(".*-{2,}\\s*\\d+\\s*$")
                    || (t.length() < 180 && t.matches(".*[A-Za-z].*") && t.matches(".*\\s{2,}\\d+\\s*$"))
                    || (t.length() < 150 && t.matches(".*\\d+\\.\\d+\\s+.*\\.\\.{2,}.*"))
                    || t.matches("\\[.*\\]\\(#.*\\)"); // markdown-link TOC
            if (toc) tocLines++;
            else contentLines++;
        }
        if (tocLines >= 3) return true;
        if (tocLines > 0 && contentLines == 0) return true;
        return tocLines > 0 && ((double) tocLines / (tocLines + contentLines)) >= 0.3;
    }

    /**
     * Count how many query keywords appear in a text chunk.
     * Higher count = more relevant to the query.
     */
    /** Parse "jev,llm" style provider lists into ordered, deduplicated tokens. */
    private List<String> parseProviders(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String part : raw.toLowerCase(Locale.ROOT).split(",")) {
            String v = part.trim();
            if ((v.equals("llm") || v.equals("jev")) && !out.contains(v)) out.add(v);
        }
        return out;
    }

    private int countKeywords(String text, Set<String> keywords) {
        int count = 0;
        String stemmed = stemText(text);
        for (String kw : keywords) {
            if (text.contains(kw) || stemmed.contains(stemText(kw))) count++;
        }
        return count;
    }

    /**
     * Conversation memory class to store chat history
     */
    @lombok.Data
    private static class ConversationMemory {
        private final List<ChatMessage> messages = new ArrayList<>();
        private final int maxSize;

        public ConversationMemory(int maxSize) {
            this.maxSize = maxSize;
        }

        public void addMessage(ChatMessage message) {
            messages.add(message);
            // Keep only last maxSize messages
            if (messages.size() > maxSize) {
                messages.remove(0);
            }
        }

        public List<ChatMessage> getMessages() {
            return Collections.unmodifiableList(messages);
        }

        public void clear() {
            messages.clear();
        }
    }
}
