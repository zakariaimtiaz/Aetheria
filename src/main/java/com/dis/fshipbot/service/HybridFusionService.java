package com.dis.fshipbot.service;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion for hybrid retrieval.
 *
 * <p>Fuses dense vector hits (search by meaning) with lexical hits (search by
 * exact keywords / BM25 / tsvector) so exact codes, names and amounts
 * ("4.2.1", "BDT 50,000") are not lost by pure cosine similarity.
 */
@Slf4j
@Service
public class HybridFusionService {

    @Value("${rag.hybrid.rrf-k:60}")
    private int rrfK;

    @Value("${rag.hybrid.table-boost:0.08}")
    private double tableBoost;

    /**
     * Fuse two ranked lists via RRF. Lists are rank-ordered (best first).
     * Dedupes on file_name + chunk_index + text hash so the same chunk
     * found by both paths gets the sum of both reciprocal ranks.
     */
    public List<EmbeddingMatch<TextSegment>> fuse(
            List<EmbeddingMatch<TextSegment>> vectorHits,
            List<EmbeddingMatch<TextSegment>> lexicalHits,
            boolean isListQuestion) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, EmbeddingMatch<TextSegment>> best = new HashMap<>();
        Map<String, Double> bestVectorScore = new HashMap<>();

        addList(vectorHits, scores, best, bestVectorScore, true);
        addList(lexicalHits, scores, best, bestVectorScore, false);

        List<Map.Entry<String, Double>> ranked = new ArrayList<>(scores.entrySet());
        ranked.sort(Map.Entry.<String, Double>comparingByValue().reversed());

        List<EmbeddingMatch<TextSegment>> out = new ArrayList<>();
        for (Map.Entry<String, Double> e : ranked) {
            EmbeddingMatch<TextSegment> m = best.get(e.getKey());
            if (m == null) {
                continue;
            }
            double fused = e.getValue();
            // Prefer whole tables for list/compare questions so all rows travel together.
            if (isListQuestion && isTable(m)) {
                fused += tableBoost;
            }
            out.add(new EmbeddingMatch<>(fused, m.embeddingId(), m.embedding(), m.embedded()));
        }
        out.sort(Comparator.comparingDouble((EmbeddingMatch<TextSegment> m) -> m.score()).reversed());
        return out;
    }

    private void addList(List<EmbeddingMatch<TextSegment>> hits,
                         Map<String, Double> scores,
                         Map<String, EmbeddingMatch<TextSegment>> best,
                         Map<String, Double> bestVectorScore,
                         boolean isVector) {
        if (hits == null) {
            return;
        }
        for (int i = 0; i < hits.size(); i++) {
            EmbeddingMatch<TextSegment> m = hits.get(i);
            if (m == null || m.embedded() == null) {
                continue;
            }
            String key = fusionKey(m.embedded());
            double rrf = 1.0 / (rrfK + i + 1);
            scores.put(key, scores.getOrDefault(key, 0.0) + rrf);
            Double prevVec = bestVectorScore.get(key);
            double curVec = isVector ? m.score() : -1.0;
            if (!best.containsKey(key) || (isVector && (prevVec == null || curVec > prevVec))) {
                best.put(key, m);
                if (isVector) {
                    bestVectorScore.put(key, m.score());
                }
            } else if (!isVector && !bestVectorScore.containsKey(key)) {
                // Lexical-only hit: keep it so exact-term chunks are not lost.
                best.putIfAbsent(key, m);
            }
        }
    }

    private String fusionKey(TextSegment seg) {
        String file = seg.metadata() != null && seg.metadata().get("file_name") != null
                ? seg.metadata().get("file_name") : "";
        String idx = seg.metadata() != null && seg.metadata().get("chunk_index") != null
                ? seg.metadata().get("chunk_index") : "";
        String text = seg.text() != null ? seg.text().trim() : "";
        int hash = text.length() > 200 ? text.substring(0, 200).hashCode() : text.hashCode();
        return file + "|" + idx + "|" + hash;
    }

    private boolean isTable(EmbeddingMatch<TextSegment> m) {
        try {
            Object v = m.embedded().metadata() != null ? m.embedded().metadata().get("content_type") : null;
            return "table".equals(String.valueOf(v));
        } catch (Exception e) {
            return false;
        }
    }
}
