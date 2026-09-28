package com.dis.fshipbot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only retrieval diagnostics. Exists because the vector-index settings were
 * invisible from inside the app: {@code ivfflat.probes} defaults to 1 in
 * PostgreSQL, which silently limits a search to a fraction of the corpus, and
 * there was no way to see that from the admin UI or logs.
 *
 * <p>Every method is best-effort — a missing extension/table must never break a
 * user query, so failures are reported as an error field instead of thrown.
 */
@Slf4j
@Service
public class RetrievalDiagnosticsService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${vector.store.ivfflat-probes:100}")
    private int configuredProbes;

    @Value("${vector.store.table-name:knowledge_base}")
    private String tableName;

    @Value("${vector.store.schema:fship_ai}")
    private String schemaName;

    /** Corpus size + table health. */
    public Map<String, Object> corpus() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT count(*) AS chunks, count(DISTINCT file_name) AS files, "
                    + "count(*) FILTER (WHERE metadata->>'content_type' = 'table') AS table_chunks, "
                    + "count(*) FILTER (WHERE section_header IS NULL) AS null_sections, "
                    + "round(avg(length(text))) AS avg_chars, max(length(text)) AS max_chars "
                    + "FROM " + schemaName + "." + tableName);
            out.put("chunks", row.get("chunks"));
            out.put("files", row.get("files"));
            out.put("tableChunks", row.get("table_chunks"));
            out.put("avgChars", row.get("avg_chars"));
            out.put("maxChars", row.get("max_chars"));

            // Any chunk beyond BGE's 512-token ceiling was silently truncated by
            // the tokenizer, so its tail never entered the vector index.
            Integer oversized = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM " + schemaName + "." + tableName
                    + " WHERE length(text) > 2000", Integer.class);
            out.put("chunksOver2000Chars", oversized);
            if (oversized != null && oversized > 0) {
                out.put("warning", oversized + " chunk(s) exceed ~BGE 512 tokens and were "
                        + "silently truncated at ingest. Re-index after the table-budget fix.");
            }
        } catch (Exception e) {
            log.warn("corpus diagnostics failed: {}", e.getMessage());
            out.put("error", e.getMessage());
        }
        return out;
    }

    /** Index definitions + effective probe count. */
    public Map<String, Object> index() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> idx = jdbcTemplate.queryForList(
                    "SELECT indexname, indexdef FROM pg_indexes "
                    + "WHERE schemaname = ? AND tablename = ?",
                    schemaName, tableName);
            out.put("indexes", idx);

            Integer probes = jdbcTemplate.queryForObject("SHOW ivfflat.probes", Integer.class);
            out.put("effectiveProbes", probes);
            out.put("configuredProbes", configuredProbes);
            out.put("note", "The vector store pins ivfflat.probes per pooled connection. "
                    + "The value here is this admin connection's setting, not the store's.");

            // Extract lists= from the IVFFlat definition so probes vs lists is visible.
            for (Map<String, Object> i : idx) {
                String def = String.valueOf(i.get("indexdef"));
                if (def.contains("ivfflat")) {
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("lists\\s*=\\s*(\\d+)").matcher(def);
                    if (m.find()) {
                        out.put("ivfflatLists", Integer.parseInt(m.group(1)));
                        out.put("ivfflatSearchIsExact", configuredProbes >= Integer.parseInt(m.group(1)));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("index diagnostics failed: {}", e.getMessage());
            out.put("error", e.getMessage());
        }
        return out;
    }

    /**
     * Measures the recall gap the probes bug caused: hits at the configured probe
     * count vs an exact search. A large gap means the index was hiding chunks.
     */
    public Map<String, Object> recallProbe(String queryVectorLiteral, int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        String tbl = schemaName + "." + tableName;
        String sql = "SELECT count(*) FROM ("
                + " SELECT embedding_id FROM " + tbl
                + " WHERE embedding <=> '" + queryVectorLiteral + "'::vector"
                + " ORDER BY embedding <=> '" + queryVectorLiteral + "'::vector"
                + " LIMIT " + Math.max(1, limit) + ") t";
        try {
            Integer total = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM " + tbl, Integer.class);
            out.put("totalChunks", total);

            // Default (broken) probe count = 1
            jdbcTemplate.execute("SET ivfflat.probes = 1");
            Integer atOne = jdbcTemplate.queryForObject(sql, Integer.class);
            jdbcTemplate.execute("SET ivfflat.probes = " + Math.max(1, configuredProbes));
            Integer atConfigured = jdbcTemplate.queryForObject(sql, Integer.class);

            out.put("hitsAtProbes1", atOne);
            out.put("hitsAtConfiguredProbes", atConfigured);
            out.put("configuredProbes", configuredProbes);
            if (atOne != null && atConfigured != null && atOne < atConfigured) {
                out.put("warning", "ivfflat.probes=1 found " + atOne + " hits but the configured "
                        + "probes found " + atConfigured + ". The default was hiding chunks.");
            }
        } catch (Exception e) {
            log.warn("recall probe failed: {}", e.getMessage());
            out.put("error", e.getMessage());
        }
        return out;
    }
}
