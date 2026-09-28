package com.dis.fshipbot.ingestion;

import com.dis.fshipbot.repository.DocumentRepository;
import com.dis.fshipbot.util.ContentHashUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.html.HtmlParser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Ingests configured web pages into the same pgvector table as local documents.
 *
 * <p>Design notes (each of these was a real defect in the previous version):
 *
 * <ul>
 *   <li><b>Atomic swap.</b> All chunks are embedded BEFORE the database is
 *       touched, then old rows are deleted and new rows inserted in ONE
 *       {@link TransactionTemplate} transaction. The old code deleted first and
 *       swallowed per-batch errors, so a mid-run failure left the page with
 *       <i>no</i> content while still reporting success.</li>
 *   <li><b>Cross-page boilerplate removal.</b> Measured on friendship.ngo,
 *       ~70% of Tika's extracted text is nav / footer / cookie-banner chrome
 *       repeated across pages. Embedding that raw would spend the retrieval
 *       budget in {@code ChatService} on boilerplate instead of content, so
 *       lines shared by multiple pages are dropped before splitting.</li>
 *   <li><b>URL validation.</b> {@code https:host/path} (no "//") parses as an
 *       OPAQUE java.net.URL with an EMPTY host and does not throw — it fails
 *       later with a cryptic connection error. Rejected up front with the fix.</li>
 *   <li><b>HTTP status + content type checked</b> before parsing, so a 404 page
 *       is never embedded as if it were content.</li>
 *   <li><b>HTML parser only.</b> The generic Tika auto-detect parser probes for
 *       tesseract / ffmpeg / exiftool on every parse and logs three stack traces
 *       at DEBUG each time. We only ever fetch HTML, so we use HtmlParser
 *       directly and pay none of that.</li>
 *   <li><b>file_name metadata</b> is set to the URL so reference badges resolve
 *       ({@code ChatService} reads the file_name metadata key, not the column).</li>
 * </ul>
 *
 * <p>Boilerplate removal is <b>corpus-relative</b>: the set of stripped lines
 * depends on which URLs are configured together. Adding or removing a URL
 * therefore changes the cleaned text (and thus the content hash) of the other
 * pages, so they re-embed on the next run. That is intended — it keeps the
 * stored text consistent with the stripping rules in force.
 */
@Slf4j
@Component
public class WebContentProcessor {

    /** Skip lines shorter than this when computing boilerplate (single words, noise). */
    private static final int BOILERPLATE_MIN_LINE = 12;

    private static final String INSERT_CHUNK_SQL =
            "INSERT INTO knowledge_base (" +
            "embedding_id, file_name, file_type, file_hash, file_size, " +
            "last_modified, processed_date, text, embedding, chunk_index, " +
            "total_chunks, chunk_start, chunk_end, metadata, \"version\") " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::vector, ?, ?, ?, ?, ?::jsonb, ?)";

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private DocumentSplitter documentSplitter;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private ContentHashUtil contentHashUtil;

    @Autowired(required = false)
    private DocNoiseStripper docNoiseStripper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Value("${web.urls:}")
    private List<String> webUrls;

    @Value("${web.batch-size:10}")
    private int batchSize;

    @Value("${web.timeout-seconds:30}")
    private int timeoutSeconds;

    @Value("${web.user-agent:Mozilla/5.0 (compatible; FriendshipBot/1.0)}")
    private String userAgent;

    /**
     * Minimum characters of cleaned text for a page to be considered indexable.
     * Below this the page is reported as FAILED rather than silently stored as
     * "new with 0 segments" — the most common symptom of a JS-rendered page or a
     * page that is nothing but navigation.
     */
    @Value("${web.min-content-chars:300}")
    private int minContentChars;

    /**
     * Minimum cleaned characters required BEFORE boilerplate is stripped. If a
     * page falls under this after stripping we keep the unstripped text rather
     * than store an empty page.
     */
    @Value("${web.min-after-strip-chars:200}")
    private int minAfterStripChars;

    private int effectiveBatchSize() {
        return batchSize > 0 ? batchSize : 10;
    }

    private int effectiveTimeoutSeconds() {
        return timeoutSeconds > 0 ? timeoutSeconds : 30;
    }

    private int effectiveMinContentChars() {
        return minContentChars > 0 ? minContentChars : 300;
    }

    private int effectiveMinAfterStripChars() {
        return minAfterStripChars > 0 ? minAfterStripChars : 200;
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * HTML-only parser. Avoids the auto-detect parser, which spawns probes for
     * tesseract / ffmpeg / exiftool on every call and logs stack traces.
     * BodyContentHandler(-1) = unlimited length (default would truncate at 100k).
     */
    private static final DocumentParser HTML_PARSER = inputStream -> {
        try {
            BodyContentHandler handler = new BodyContentHandler(-1);
            // Note the argument order: HtmlParser overrides Parser.parse and takes
            // Metadata BEFORE ParseContext (the reverse of the interface signature).
            new HtmlParser().parse(inputStream, handler, new Metadata(), new ParseContext());
            return new Document(handler.toString());
        } catch (Exception e) {
            throw new IllegalStateException("HTML parse failed: " + e.getMessage(), e);
        }
    };

    /**
     * @param forceAll when true, skip the content-hash check and re-embed every
     *                 URL. Required after a {@link DocumentSplitter} change —
     *                 page bytes are unchanged by a chunker change, so the hash
     *                 matches and the page is skipped otherwise.
     */
    public ProcessingResult processConfiguredUrls(boolean forceAll) {
        ProcessingResult result = new ProcessingResult();

        List<String> urls = cleanConfiguredUrls();
        if (urls.isEmpty()) {
            log.info("No web URLs configured (web.urls) — skipping web ingestion");
            return result;
        }

        log.info("=".repeat(80));
        log.info("STARTING WEB CONTENT PROCESSING (force={})", forceAll);
        log.info("URLs configured: {}", urls.size());
        if (forceAll) {
            log.warn("FORCING full re-embed of {} URL(s), content hash check skipped", urls.size());
        }
        log.info("=".repeat(80));

        result.setTotalDocuments(urls.size());

        // ---- Phase 1: fetch + extract every page -------------------------------
        // All pages are fetched before any embedding so boilerplate can be
        // detected ACROSS pages. A page that fails to fetch is recorded and
        // excluded; it must not abort the whole run.
        List<FetchedPage> pages = new ArrayList<>();
        for (String url : urls) {
            try {
                pages.add(fetchPage(url));
            } catch (Exception e) {
                log.error("Failed to fetch {}: {}", url, e.getMessage());
                result.addFailedUrl(url, e.getMessage());
            }
        }

        if (pages.isEmpty()) {
            log.warn("No web pages could be fetched — nothing to process");
            return result;
        }

        // ---- Phase 2: corpus-relative boilerplate ------------------------------
        Set<String> boilerplate = computeBoilerplate(pages);
        if (boilerplate.isEmpty()) {
            log.info("No cross-page boilerplate detected (need >= 2 pages with shared chrome)");
        } else {
            log.info("Boilerplate: {} shared line(s) will be stripped from every page", boilerplate.size());
        }

        // ---- Phase 3: clean, split, embed, atomic swap ------------------------
        for (FetchedPage page : pages) {
            try {
                processPage(page, boilerplate, forceAll, result);
            } catch (Exception e) {
                log.error("Failed to process {}", page.url, e);
                result.addFailedUrl(page.url, e.getMessage());
            }
        }

        log.info("=".repeat(80));
        log.info("WEB CONTENT PROCESSING COMPLETE — new={} updated={} skipped={} failed={} segments={}",
                result.getNewUrls(), result.getUpdatedUrls(), result.getSkippedUrls(),
                result.getFailedUrls(), result.getTotalSegmentsStored());
        log.info("=".repeat(80));

        return result;
    }

    private List<String> cleanConfiguredUrls() {
        List<String> out = new ArrayList<>();
        if (webUrls == null) {
            return out;
        }
        for (String raw : webUrls) {
            if (raw == null) {
                continue;
            }
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            out.add(trimmed);
        }
        return out;
    }

    /**
     * Validate, fetch and extract one page. Throws with an actionable message
     * rather than letting a cryptic connection error surface later.
     */
    private FetchedPage fetchPage(String url) throws Exception {
        URL parsed = validateUrl(url);

        HttpURLConnection connection = (HttpURLConnection) parsed.openConnection();
        FetchedPage page = new FetchedPage();
        page.url = url;
        page.fetchedAt = Instant.now();
        try {
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(effectiveTimeoutSeconds() * 1000);
            connection.setReadTimeout(effectiveTimeoutSeconds() * 1000);
            connection.setRequestProperty("User-Agent", userAgent);

            int status = connection.getResponseCode();
            String contentType = connection.getContentType();
            page.finalUrl = connection.getURL().toString();

            if (status < 200 || status >= 300) {
                throw new IllegalStateException("HTTP " + status + " for " + page.finalUrl);
            }
            if (contentType == null || !contentType.toLowerCase(Locale.ROOT).contains("html")) {
                throw new IllegalStateException("Not an HTML page (content-type=" + contentType + ")");
            }

            byte[] raw;
            try (java.io.InputStream in = connection.getInputStream()) {
                raw = in.readAllBytes();
            }
            String text = HTML_PARSER.parse(new ByteArrayInputStream(raw)).text();
            page.rawText = text == null ? "" : text;
            page.title = extractTitle(text);
        } finally {
            connection.disconnect();
        }
        return page;
    }

    /**
     * Reject URLs that would parse but never connect.
     *
     * <p>{@code new URL("https:friendship.ngo")} SUCCEEDS and yields an opaque
     * URL with an empty host, so the failure would otherwise surface much later
     * as an unhelpful connection error.
     */
    static URL validateUrl(String raw) throws Exception {
        URL parsed;
        try {
            parsed = new URL(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed URL \"" + raw + "\": " + e.getMessage());
        }
        String protocol = parsed.getProtocol() == null ? "" : parsed.getProtocol().toLowerCase(Locale.ROOT);
        if (!"http".equals(protocol) && !"https".equals(protocol)) {
            throw new IllegalArgumentException(
                    "Unsupported protocol \"" + protocol + "\" in \"" + raw + "\" (only http/https)");
        }
        if (parsed.getHost() == null || parsed.getHost().isEmpty()) {
            String repaired = raw.replaceFirst("(?i)^(https?):(?!//)", "$1://");
            throw new IllegalArgumentException("URL \"" + raw + "\" has no host"
                    + (repaired.equals(raw) ? "" : " — did you mean \"" + repaired + "\"?"));
        }
        return parsed;
    }

    private String extractTitle(String html) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);
        if (!m.find()) {
            return "";
        }
        String t = m.group(1).replaceAll("\\s+", " ").trim();
        // "About Us - Friendship NGO" -> "About Us": the site name is constant
        // chrome and would otherwise be repeated in every section header.
        t = t.replaceAll("(?i)\\s*[-|–—]\\s*Friendship NGO\\s*$", "").trim();
        return t;
    }

    /**
     * Lines whose normalized form appears on at least half the fetched pages
     * (minimum 2) are treated as site chrome and stripped from all of them.
     */
    private Set<String> computeBoilerplate(List<FetchedPage> pages) {
        Set<String> boilerplate = new LinkedHashSet<>();
        if (pages.size() < 2) {
            return boilerplate;
        }
        int threshold = Math.max(2, (pages.size() + 1) / 2);

        Map<String, Integer> pageCount = new HashMap<>();
        for (FetchedPage page : pages) {
            // Set per page: one page cannot count twice towards the threshold.
            for (String norm : normalizedLines(page.rawText)) {
                pageCount.put(norm, pageCount.getOrDefault(norm, 0) + 1);
            }
        }
        for (Map.Entry<String, Integer> e : pageCount.entrySet()) {
            if (e.getValue() >= threshold) {
                boilerplate.add(e.getKey());
            }
        }
        return boilerplate;
    }

    private Set<String> normalizedLines(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) {
            return out;
        }
        for (String line : text.split("\\n")) {
            String n = normalize(line);
            if (n.length() >= BOILERPLATE_MIN_LINE) {
                out.add(n);
            }
        }
        return out;
    }

    private String normalize(String line) {
        return line == null ? "" : line.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** Drop shared chrome lines, then run the document stripper. */
    private String cleanPageText(FetchedPage page, Set<String> boilerplate) {
        String text = page.rawText;
        int removed = 0;
        if (!boilerplate.isEmpty()) {
            List<String> kept = new ArrayList<>();
            for (String line : text.split("\\n", -1)) {
                String n = normalize(line);
                if (n.length() >= BOILERPLATE_MIN_LINE && boilerplate.contains(n)) {
                    removed++;
                    continue;
                }
                kept.add(line);
            }
            text = String.join("\n", kept);
        }

        if (docNoiseStripper != null) {
            try {
                DocNoiseStripper.StripResult stripped = docNoiseStripper.strip(text);
                text = stripped.getText();
                if (stripped.getTocRemoved() + stripped.getHeaderFooterRemoved() > 0) {
                    log.info("{}: stripper removed {} TOC + {} header/footer lines",
                            page.url, stripped.getTocRemoved(), stripped.getHeaderFooterRemoved());
                }
            } catch (Exception e) {
                log.warn("DocNoiseStripper failed for {}: {}", page.url, e.getMessage());
            }
        }

        text = text.replaceAll("(?m)^[ \\t]*\\r?\\n(?:[ \\t]*\\r?\\n){2,}", "\n\n").trim();
        if (removed > 0) {
            log.info("{}: stripped {} shared boilerplate line(s)", page.url, removed);
        }
        return text;
    }

    private void processPage(FetchedPage page, Set<String> boilerplate, boolean forceAll,
                             ProcessingResult result) {
        Instant start = Instant.now();

        String cleaned = cleanPageText(page, boilerplate);

        // A page that is nothing but navigation must not be stored as a
        // successful "new" document with zero useful chunks.
        if (cleaned.length() < effectiveMinContentChars()) {
            if (!boilerplate.isEmpty() && page.rawText.length() >= effectiveMinAfterStripChars()) {
                log.warn("{}: boilerplate stripping left only {} chars — keeping unstripped text",
                        page.url, cleaned.length());
                cleaned = page.rawText;
            } else {
                throw new IllegalStateException("Only " + cleaned.length()
                        + " chars of content after cleaning (min " + effectiveMinContentChars()
                        + ") — page is likely JavaScript-rendered or navigation only");
            }
        }

        String contentHash = contentHashUtil.calculateContentHash(cleaned);
        page.cleanedText = cleaned;

        DocumentRepository.FileInfo latest = documentRepository.getLatestFileInfo(page.url);
        boolean isUpdate = latest != null;
        if (!forceAll && latest != null && contentHash.equals(latest.getFileHash())) {
            log.info("Skipping {} - content unchanged", page.url);
            result.addSkippedUrl(page.url);
            return;
        }

        // ---- Embed everything BEFORE touching the DB -------------------------
        List<PendingRow> pending = new ArrayList<>();
        List<TextSegment> segments = documentSplitter.split(buildDocument(page, cleaned));
        if (segments.isEmpty()) {
            throw new IllegalStateException("Splitter produced 0 segments from "
                    + cleaned.length() + " chars");
        }
        log.info("{}: {} segments", page.url, segments.size());

        int step = effectiveBatchSize();
        for (int i = 0; i < segments.size(); i += step) {
            List<TextSegment> batch = segments.subList(i, Math.min(i + step, segments.size()));
            List<Embedding> embeddings = embeddingModel.embedAll(batch).content();
            for (int j = 0; j < batch.size(); j++) {
                TextSegment segment = batch.get(j);
                Map<String, Object> meta = new HashMap<>(segment.metadata().asMap());
                meta.put("file_name", page.url);
                meta.put("file_type", "Web Content");
                meta.put("url", page.url);
                meta.put("domain", extractDomain(page.url));
                meta.put("page_title", page.title);
                meta.put("source_type", "webpage");
                meta.put("fetch_date", page.fetchedAt.toString());
                meta.put("processed_timestamp", page.fetchedAt.toString());
                meta.put("ingestion_source", "web");

                PendingRow row = new PendingRow();
                row.text = segment.text();
                row.embedding = embeddings.get(j).vector();
                row.metadataJson = toJson(meta);
                row.chunkIndex = i + j;
                row.chunkStart = metaInt(segment, "chunk_start");
                row.chunkEnd = metaInt(segment, "chunk_end");
                pending.add(row);
            }
        }

        // ---- Atomic swap ------------------------------------------------------
        int totalSegments = pending.size();
        final int nextVersion = documentRepository.getNextVersion(page.url);
        final String hash = contentHash;
        final int contentLength = cleaned.length();
        Timestamp now = Timestamp.from(Instant.now());
        Timestamp modified = Timestamp.from(page.fetchedAt);
        final int expected = totalSegments;
        final String url = page.url;

        Integer stored = new TransactionTemplate(transactionManager).execute(tx -> {
            documentRepository.removeByFileName(url);
            int count = 0;
            for (PendingRow row : pending) {
                int rows = jdbcTemplate.update(INSERT_CHUNK_SQL,
                        java.util.UUID.randomUUID(),
                        url,
                        "Web Content",
                        hash,
                        (long) contentLength,
                        modified,
                        now,
                        row.text,
                        row.embedding,
                        row.chunkIndex,
                        expected,
                        row.chunkStart,
                        row.chunkEnd,
                        row.metadataJson,
                        nextVersion);
                if (rows > 0) {
                    count++;
                    result.incrementSegmentsStored();
                }
            }
            return count;
        });

        if (stored == null || stored == 0) {
            throw new IllegalStateException("Atomic swap stored 0 rows — previous content preserved");
        }

        long ms = Duration.between(start, Instant.now()).toMillis();
        if (isUpdate) {
            result.addUpdatedUrl(page.url);
            log.info("Updated {} ({} segments, {} ms)", page.url, stored, ms);
        } else {
            result.addNewUrl(page.url);
            log.info("Ingested {} ({} segments, {} ms)", page.url, stored, ms);
        }
    }

    /**
     * A markdown header gives the splitter a section_header, so retrieved web
     * chunks are labelled with the page they came from instead of appearing
     * anonymous in the model context.
     */
    private Document buildDocument(FetchedPage page, String cleaned) {
        Document document = Document.from(cleaned);
        String header = page.title.isEmpty() ? page.url : page.title;
        document.metadata().put("file_name", page.url);
        document.metadata().put("file_type", "Web Content");
        document.metadata().put("url", page.url);
        document.metadata().put("domain", extractDomain(page.url));
        document.metadata().put("page_title", page.title);
        document.metadata().put("source_type", "webpage");
        document.metadata().put("fetch_date", page.fetchedAt.toString());
        return document;
    }

    private String toJson(Map<String, Object> meta) {
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize chunk metadata: " + e.getMessage(), e);
        }
    }

    private int metaInt(TextSegment segment, String key) {
        Object v = segment.metadata().get(key);
        if (v == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String extractDomain(String url) {
        try {
            String host = new URL(url).getHost();
            return host == null ? "unknown"
                    : (host.startsWith("www.") ? host.substring(4) : host);
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static final class FetchedPage {
        String url;
        String finalUrl;
        String title = "";
        String rawText;
        String cleanedText;
        Instant fetchedAt;
    }

    private static final class PendingRow {
        String text;
        float[] embedding;
        String metadataJson;
        int chunkIndex;
        int chunkStart;
        int chunkEnd;
    }

    @lombok.Data
    public static class ProcessingResult {
        private int totalDocuments;
        private int newUrls;
        private int updatedUrls;
        private int skippedUrls;
        private int failedUrls;
        private int totalSegmentsStored;
        private String error;

        private List<String> newUrlsList = new ArrayList<>();
        private List<String> updatedUrlsList = new ArrayList<>();
        private List<String> skippedUrlsList = new ArrayList<>();
        private Map<String, String> failedUrlsMap = new LinkedHashMap<>();

        public void addNewUrl(String url) {
            newUrls++;
            newUrlsList.add(url);
        }

        public void addUpdatedUrl(String url) {
            updatedUrls++;
            updatedUrlsList.add(url);
        }

        public void addSkippedUrl(String url) {
            skippedUrls++;
            skippedUrlsList.add(url);
        }

        public void addFailedUrl(String url, String reason) {
            failedUrls++;
            failedUrlsMap.put(url, reason);
        }

        public void incrementSegmentsStored() {
            totalSegmentsStored++;
        }

        // Aliases so IngestionService can log web results like the others.
        public int getNewDocuments() { return newUrls; }
        public int getUpdatedDocuments() { return updatedUrls; }
        public int getSkippedDocuments() { return skippedUrls; }
        public int getFailedDocuments() { return failedUrls; }
        public List<String> getNewFiles() { return newUrlsList; }
        public List<String> getUpdatedFiles() { return updatedUrlsList; }
        public List<String> getSkippedFiles() { return skippedUrlsList; }
        public Map<String, String> getFailedFiles() { return failedUrlsMap; }
    }
}
