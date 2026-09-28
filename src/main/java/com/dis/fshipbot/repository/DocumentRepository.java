package com.dis.fshipbot.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

@Slf4j
@Repository
public class DocumentRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Memoized: the content_tsv column never appears/disappears within a JVM
     * lifetime, so one information_schema lookup is enough. Previously this ran
     * on EVERY lexical search (an extra roundtrip per user question).
     */
    private volatile Boolean contentTsvPresent;

    @Transactional
    public boolean isFileProcessed(String fileName, String fileHash) {
        String sql = "SELECT COUNT(*) FROM knowledge_base " +
                "WHERE file_name = ? AND file_hash = ? ";

        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, fileName, fileHash);
        return count != null && count > 0;
    }

    @Transactional
    public FileInfo getLatestFileInfo(String fileName) {
        String sql = "SELECT file_name, file_hash, file_size, last_modified, \"version\" " +
                "FROM knowledge_base " +
                "WHERE file_name = ? " +
                "ORDER BY \"version\" DESC LIMIT 1";

        try {
            return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> {
                FileInfo info = new FileInfo();
                info.setFileName(rs.getString("file_name"));
                info.setFileHash(rs.getString("file_hash"));
                info.setFileSize(rs.getLong("file_size"));
                info.setLastModified(rs.getTimestamp("last_modified") != null ?
                        rs.getTimestamp("last_modified").toInstant() : null);
                info.setVersion(rs.getInt("version"));
                return info;
            }, fileName);
        } catch (Exception e) {
            return null;
        }
    }

    public void removeByFileName(String fileName) {
        String sql = "DELETE FROM knowledge_base WHERE file_name = ?";
        int rows = jdbcTemplate.update(sql, fileName);
        log.info("Deleted {} records for file: {}", rows, fileName);
    }

    /**
     * Delete rows sharing the same basename regardless of extension.
     * One-time orphan cleanup for legacy rows stored under mapped original names
     * (e.g. "HR_Policy.pdf") after the filename_mapping.csv layer was removed.
     */
    public void removeByBaseName(String baseName) {
        String sql = "DELETE FROM knowledge_base WHERE regexp_replace(file_name, '\\.[^.]+$', '') = ?";
        int rows = jdbcTemplate.update(sql, baseName);
        if (rows > 0) {
            log.info("Deleted {} legacy records for basename: {}", rows, baseName);
        }
    }

    @Transactional
    public int getNextVersion(String fileName) {
        String sql = "SELECT COALESCE(MAX(\"version\"), 0) + 1 FROM knowledge_base " +
                "WHERE file_name = ?";
        return jdbcTemplate.queryForObject(sql, Integer.class, fileName);
    }

    /**
     * Lexical search (keyword path for hybrid retrieval).
     *
     * <p>Tries {@code content_tsv} (V3 migration) with {@code plainto_tsquery}
     * first; falls back to per-keyword {@code ILIKE} so hybrid works even
     * before the migration is applied or when pg_trgm is unavailable.
     * Returns at most {@code limit} rows ordered by lexical relevance.
     */
    public java.util.List<java.util.Map<String, Object>> lexicalSearch(String query, int limit) {
        java.util.List<String> keywords = extractLexicalKeywords(query);
        if (keywords.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        int safeLimit = Math.max(1, Math.min(limit, 200));

        // Fast path: full-text index if migration applied.
        // OR semantics per keyword: the raw query is an AND under plainto_tsquery
        // (every lexeme must match), which guarantees zero hits once expansion
        // appends terms like "chuti"/"probation" no single chunk contains together.
        if (hasContentTsvColumn()) {
            try {
                StringBuilder where = new StringBuilder();
                StringBuilder rank = new StringBuilder();
                java.util.List<Object> tsArgs = new java.util.ArrayList<>();
                for (int i = 0; i < keywords.size(); i++) {
                    if (i > 0) {
                        where.append(" OR ");
                        rank.append(" + ");
                    }
                    where.append("COALESCE(content_tsv, to_tsvector('english', text)) "
                            + "@@ plainto_tsquery('english', ?)");
                    rank.append("ts_rank_cd(COALESCE(content_tsv, to_tsvector('english', text)), "
                            + "plainto_tsquery('english', ?))");
                }
                for (String kw : keywords) {
                    tsArgs.add(kw);
                }
                for (String kw : keywords) {
                    tsArgs.add(kw);
                }
                tsArgs.add(safeLimit);
                String sql = "SELECT embedding_id, file_name, text, metadata, "
                        + "(" + rank + ") AS rank "
                        + "FROM knowledge_base WHERE " + where + " "
                        + "ORDER BY rank DESC LIMIT ?";
                java.util.List<java.util.Map<String, Object>> rows =
                        jdbcTemplate.queryForList(sql, tsArgs.toArray());
                if (!rows.isEmpty()) {
                    return rows;
                }
                // Empty (not error): fall through to ILIKE below.
            } catch (Exception e) {
                log.warn("content_tsv search failed, falling back to ILIKE: {}", e.getMessage());
            }
        }

        // Fallback: ILIKE per keyword, score = number of keyword hits.
        try {
            StringBuilder sql = new StringBuilder(
                    "SELECT embedding_id, file_name, text, metadata FROM knowledge_base WHERE ");
            java.util.List<Object> args = new java.util.ArrayList<>();
            for (int i = 0; i < keywords.size(); i++) {
                if (i > 0) {
                    sql.append(" OR ");
                }
                sql.append("text ILIKE ?");
                args.add("%" + keywords.get(i) + "%");
            }
            // Cap keywords to keep the query planner happy.
            int capped = Math.min(keywords.size(), 12);
            if (capped < keywords.size()) {
                // Rebuild with capped list.
                sql = new StringBuilder(
                        "SELECT embedding_id, file_name, text, metadata FROM knowledge_base WHERE ");
                args.clear();
                for (int i = 0; i < capped; i++) {
                    if (i > 0) {
                        sql.append(" OR ");
                    }
                    sql.append("text ILIKE ?");
                    args.add("%" + keywords.get(i) + "%");
                }
            }
            sql.append(" LIMIT ?");
            args.add(safeLimit * 2);
            java.util.List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
            // Rank in Java by keyword hit count (stable, no extra SQL).
            rows.sort((a, b) -> Integer.compare(countHits(String.valueOf(b.get("text")), keywords),
                    countHits(String.valueOf(a.get("text")), keywords)));
            return rows.size() > safeLimit ? rows.subList(0, safeLimit) : rows;
        } catch (Exception e) {
            log.warn("Lexical ILIKE search failed: {}", e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    private boolean hasContentTsvColumn() {        try {
            Boolean cached = contentTsvPresent;
            if (cached != null) {
                return cached;
            }
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns "
                    + "WHERE table_schema='fship_ai' AND table_name='knowledge_base' "
                    + "AND column_name='content_tsv'", Integer.class);
            boolean present = count != null && count > 0;
            contentTsvPresent = present;
            return present;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Bangla transliterations injected by query-expansion synonyms
     * ({@code chuti}, {@code bhatta}, {@code beton}, {@code eid}, {@code puja}).
     * The indexed documents are English prose, so these tokens can never match
     * lexically — they only add noise (and AND-semantics poison) to the keyword
     * path. The embedding query keeps them (vector space is tolerant); the
     * lexical path drops them here.
     */
    private static final java.util.Set<String> BANGLA_LEXICAL_EXCLUDE =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "chuti", "bhatta", "beton", "eid", "puja"));

    /** Plain English stopwords: as OR-branches they only dilute lexical rank. */
    private static final java.util.Set<String> LEXICAL_STOPWORDS =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "the", "a", "an", "is", "are", "was", "were", "be", "been",
                    "have", "has", "had", "do", "does", "did", "will", "would",
                    "could", "should", "may", "might", "shall", "can", "about",
                    "what", "which", "who", "whom", "this", "that", "these",
                    "those", "and", "or", "for", "with", "from", "its", "tell",
                    "please", "kindly", "give", "show", "list"));

    private java.util.List<String> extractLexicalKeywords(String query) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (query == null) {
            return out;
        }
        // Keep section codes (4.2.1), amounts, and words len>=3; drop stop-words lightly.
        for (String tok : query.toLowerCase().replaceAll("[^a-z0-9.\\s-]", " ").split("\\s+")) {
            String t = tok.trim();
            if (t.length() < 3 && !t.matches("\\d+(\\.\\d+)+")) {
                continue;
            }
            if (t.length() > 40) {
                continue;
            }
            if (BANGLA_LEXICAL_EXCLUDE.contains(t)) {
                continue;
            }
            if (LEXICAL_STOPWORDS.contains(t)) {
                continue;
            }
            if (!out.contains(t)) {
                out.add(t);
            }
            if (out.size() >= 12) {
                break;
            }
        }
        return out;
    }

    private int countHits(String text, java.util.List<String> keywords) {
        if (text == null) {
            return 0;
        }
        String lower = text.toLowerCase();
        int hits = 0;
        for (String k : keywords) {
            if (lower.contains(k)) {
                hits++;
            }
        }
        return hits;
    }

    @Transactional
    public void saveDocumentChunk(Map<String, Object> params) {
        String sql =
            "INSERT INTO knowledge_base (" +
            "id, file_name, file_type, file_hash, file_size, " +
            "last_modified, processed_date, text, embedding, chunk_index, " +
            "total_chunks, chunk_start, chunk_end, metadata, \"version\") " +
            "VALUES (" +
            "gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?::vector, ?, " +
            "?, ?, ?, ?::jsonb, ?)";

        jdbcTemplate.update(sql,
                params.get("file_name"),
                params.get("file_type"),
                params.get("file_hash"),
                params.get("file_size"),
                params.get("last_modified"),
                params.get("processed_date"),
                params.get("text"),
                params.get("embedding"),
                params.get("chunk_index"),
                params.get("total_chunks"),
                params.get("chunk_start"),
                params.get("chunk_end"),
                params.get("metadata"),
                params.get("version")
        );
    }

    @lombok.Data
    public static class FileInfo {
        private String fileName;
        private String fileHash;
        private Long fileSize;
        private Instant lastModified;
        private Integer version;
    }
}