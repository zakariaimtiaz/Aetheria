package com.dis.fshipbot.ingestion;

import com.dis.fshipbot.repository.DocumentRepository;
import com.dis.fshipbot.util.FileHashUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Component
public class DocumentProcessor {

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private DocumentSplitter documentSplitter;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private FileHashUtil fileHashUtil;

    @Autowired
    private DocNoiseStripper docNoiseStripper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Value("${docs.directory}")
    private String docsDirectory;

    @Value("${docs.batch-size:20}")
    private int batchSize;

    /**
     * Pre-compiled: String.matches()/replaceAll() compile a fresh Pattern on
     * EVERY call — these run per line of every document on the ingest hot path.
     */
    private static final java.util.regex.Pattern FOOTER_PAGE =
            java.util.regex.Pattern.compile("(?i)^(Page|P\\.)\\s*\\d+\\s*(of|/\\s*\\d+)?\\s*$");
    private static final java.util.regex.Pattern FOOTER_VERSION =
            java.util.regex.Pattern.compile("(?i)^.{0,10}(Version|Ver)\\s*[:.]?\\s*\\d+\\.\\d+\\s*$");
    private static final java.util.regex.Pattern FOOTER_COPY =
            java.util.regex.Pattern.compile("(?i)^(©.*|Copyright.*|Confidential.*|All rights reserved.*)$");
    private static final java.util.regex.Pattern TABLE_MONEY =
            java.util.regex.Pattern.compile(".*\\b(BDT|DDT|Taka|USD|Tk\\.|\\d{1,3},\\d{3})\\b.*");
    private static final java.util.regex.Pattern TABLE_LEAD_NUM =
            java.util.regex.Pattern.compile("^\\d+\\s+.*");
    private static final java.util.regex.Pattern TABLE_SHORT_TITLE =
            java.util.regex.Pattern.compile("^[A-Z][a-z]+\\s+[A-Z].*");
    private static final java.util.regex.Pattern TABLE_GROUPED_NUM =
            java.util.regex.Pattern.compile(".*\\d{1,3},\\d{3}.*");
    private static final java.util.regex.Pattern TABLE_CURR =
            java.util.regex.Pattern.compile(".*\\b(BDT|DDT|Tk\\.)\\b.*");
    private static final java.util.regex.Pattern TABLE_NUM_TITLE =
            java.util.regex.Pattern.compile("^\\d+\\s+[A-Z].*");

    /**
     * Core ingestion loop.
     *
     * @param forceAll when true, skip the content-hash check and re-embed every
     *                 file. This is the ONLY way to force a full re-index — the
     *                 admin "Force full re-embed" checkbox posts
     *                 {@code /admin/documents/ingest?force=true}. Incremental
     *                 runs rebuild only files whose hash changed.
     */
    public ProcessingResult processAllDocuments(boolean forceAll) {
        boolean force = forceAll;
        log.info("=".repeat(80));
        log.info("📄 STARTING LOCAL MARKDOWN INGESTION PIPELINE");
        log.info("=".repeat(80));
        log.info("Directory: {}", docsDirectory);
        log.info("Batch Size: {}", batchSize);
        log.info("Force mode: {}", force
                ? "FULL RE-INDEX (hash check skipped)"
                : "incremental (unaltered files skipped)");
        log.info("=".repeat(80));

        ProcessingResult result = new ProcessingResult();
        Path docsPath = Paths.get(docsDirectory);

        if (!Files.exists(docsPath)) {
            log.error("Documents directory does not exist: {}", docsDirectory);
            result.setError("Directory not found");
            return result;
        }

        try (Stream<Path> walk = Files.walk(docsPath)) {
            List<Path> documentPaths = walk
                    .filter(Files::isRegularFile)
                    .filter(this::isMarkdownFile) // Restrict strictly to markdown targets
                    .collect(Collectors.toList());

            log.info("Found {} Markdown files to process", documentPaths.size());
            result.setTotalDocuments(documentPaths.size());

            if (force) {
                log.warn("Forcing full re-ingest, hash check skipped ({} files will re-embed)", documentPaths.size());
            }

            for (Path docPath : documentPaths) {
                String fileName = docPath.getFileName().toString();
                log.info("Checking file tracking status: {}", fileName);

                FileProcessingStatus status = force ? FileProcessingStatus.CHANGED : checkFileStatus(docPath);

                if (status == FileProcessingStatus.UNCHANGED) {
                    log.info("⏭️ Skipping {} - content hash is unmodified", fileName);
                    result.addSkippedDocument(fileName);
                    continue;
                }

                try {
                    boolean success = processSingleDocument(docPath, result, status);
                    if (success) {
                        if (status == FileProcessingStatus.NEW) {
                            result.addNewDocument(fileName);
                        } else if (status == FileProcessingStatus.CHANGED) {
                            result.addUpdatedDocument(fileName);
                        }
                    }
                } catch (Exception e) {
                    log.error("Error processing document: {}", docPath, e);
                    result.addFailedDocument(fileName, e.getMessage());
                }
            }

            log.info("=".repeat(80));
            log.info("✅ DOCUMENT PROCESSING COMPLETED");
            log.info("   - Total Documents: {}", result.getTotalDocuments());
            log.info("   - New Documents: {}", result.getNewDocuments());
            log.info("   - Updated Documents: {}", result.getUpdatedDocuments());
            log.info("   - Skipped: {}", result.getSkippedDocuments());
            log.info("   - Failed: {}", result.getFailedDocuments());
            log.info("   - Total Segments Stored: {}", result.getTotalSegmentsStored());
            log.info("=".repeat(80));

        } catch (Exception e) {
            log.error("Error walking through documents directory", e);
            result.setError(e.getMessage());
        }

        return result;
    }

    private FileProcessingStatus checkFileStatus(Path docPath) {
        try {
            // File name is stored directly — no mapping layer.
            String fileName = xtractFileName(docPath.getFileName().toString());
            String fileHash = fileHashUtil.calculateSHA256(docPath);
            long fileSize = Files.size(docPath);

            DocumentRepository.FileInfo latestFile = documentRepository.getLatestFileInfo(fileName);

            if (latestFile == null) {
                return FileProcessingStatus.NEW;
            }

            if (latestFile.getFileHash() != null && latestFile.getFileHash().equals(fileHash) &&
                    latestFile.getFileSize() != null && latestFile.getFileSize() == fileSize) {
                return FileProcessingStatus.UNCHANGED;
            }

            return FileProcessingStatus.CHANGED;

        } catch (Exception e) {
            log.error("Error checking file status for: {}", docPath, e);
            return FileProcessingStatus.NEW;
        }
    }

    /**
     * Single source of truth for the chunk INSERT (was rebuilt per row).
     */
    private static final String INSERT_CHUNK_SQL =
            "INSERT INTO knowledge_base (" +
            "embedding_id, file_name, file_type, file_hash, file_size, " +
            "last_modified, processed_date, text, embedding, chunk_index, " +
            "total_chunks, chunk_start, chunk_end, metadata, \"version\") " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::vector, ?, ?, ?, ?, ?::jsonb, ?)";

    /** One fully-embedded chunk waiting for the atomic DB swap. */
    private static final class PendingRow {
        String text;
        float[] embedding;
        String metadataJson;
        int chunkIndex;
        int chunkStart;
        int chunkEnd;
    }

    boolean processSingleDocument(Path docPath, ProcessingResult result, FileProcessingStatus status) {
        String rawFileName = docPath.getFileName().toString();
        String fileType = xtractFileType(rawFileName);
        // Store the .md file name directly — no mapping layer.
        String fileName = xtractFileName(rawFileName);
        log.info("📄 File State [{}]: Processing -> {} (Type: {})", status, fileName, fileType);
        Instant docStart = Instant.now();

        try {
            String fileHash = fileHashUtil.calculateSHA256(docPath);
            long fileSize = Files.size(docPath);
            long lastModified = fileHashUtil.getLastModified(docPath);
            int nextVersion = documentRepository.getNextVersion(fileName);

            // =========================================================================
            // STEP A: READ LOCAL MARKDOWN DATA DIRECTLY (Preserving UTF-8)
            // =========================================================================
            String rawMarkdownContent = Files.readString(docPath, StandardCharsets.UTF_8);

            if (rawMarkdownContent.trim().isEmpty()) {
                log.warn("⚠️ Local markdown file is completely empty: {}", rawFileName);
                return false;
            }

            // =========================================================================
            // STEP B: CLEAN AND SPLIT TEXT
            // =========================================================================
            String cleanedMarkdown = cleanDocumentText(rawMarkdownContent);
            Document document = Document.from(cleanedMarkdown, Metadata.from("file_name", fileName));
            List<TextSegment> segments = documentSplitter.split(document);

            if (segments.isEmpty()) {
                log.warn("⚠️ Text splitting generated zero chunks for {}. Aborting storage routine.", fileName);
                return false;
            }

            log.info("🧩 Created {} structural segments from markdown file.", segments.size());

            // =========================================================================
            // STEP C: EMBED ALL BATCHES — OUTSIDE any DB transaction.
            // ONNX embedding is slow CPU work; a DB transaction must never be held
            // open across it (pool starvation). Nothing is deleted yet, so any
            // failure here leaves the old chunks untouched and the file retries
            // cleanly on the next run.
            // =========================================================================
            int totalSegments = segments.size();
            List<PendingRow> pending = new ArrayList<>(totalSegments);
            ObjectMapper objectMapper = new ObjectMapper();

            for (int i = 0; i < segments.size(); i += batchSize) {
                int end = Math.min(i + batchSize, segments.size());
                List<TextSegment> batch = segments.subList(i, end);
                int batchNumber = (i / batchSize) + 1;
                int totalBatches = (segments.size() + batchSize - 1) / batchSize;

                List<Embedding> batchEmbeddings = embeddingModel.embedAll(batch).content();

                for (int j = 0; j < batch.size(); j++) {
                    TextSegment segment = batch.get(j);

                    Map<String, Object> metadataMap = new HashMap<>(segment.metadata().asMap());
                    metadataMap.put("file_name", fileName);
                    metadataMap.put("file_type", fileType);
                    metadataMap.put("processed_timestamp", Instant.now().toString());
                    metadataMap.put("ingestion_source", "local_markdown");

                    PendingRow row = new PendingRow();
                    row.text = segment.text();
                    row.embedding = batchEmbeddings.get(j).vector();
                    row.metadataJson = objectMapper.writeValueAsString(metadataMap);
                    row.chunkIndex = i + j;
                    row.chunkStart = segment.metadata().get("chunk_start") != null ?
                            Integer.parseInt(segment.metadata().get("chunk_start")) : 0;
                    row.chunkEnd = segment.metadata().get("chunk_end") != null ?
                            Integer.parseInt(segment.metadata().get("chunk_end")) : 0;
                    pending.add(row);
                }
                log.info("   ✅ Batch {}/{} embedded", batchNumber, totalBatches);
            }

            // =========================================================================
            // STEP D: ATOMIC SWAP — delete old + insert all new inside ONE transaction.
            // Previously the delete ran first with no transaction (@Transactional was
            // a no-op via self-invocation), so a mid-file failure permanently emptied
            // the document and the hash check then refused to re-ingest it.
            // TransactionTemplate is used directly (no AOP proxy involved).
            // =========================================================================
            log.info("🗑️ Replacing vector data for: {} ({} segments)", fileName, pending.size());
            Timestamp now = Timestamp.from(Instant.now());
            Timestamp modified = Timestamp.from(Instant.ofEpochMilli(lastModified));
            int processedSegments = new TransactionTemplate(transactionManager).execute(txStatus -> {
                // Old chunks go only AFTER the new ones are fully embedded above.
                // Basename cleanup drops stale rows from the legacy mapped-name era.
                documentRepository.removeByFileName(fileName);
                documentRepository.removeByBaseName(stripExtension(fileName));

                int stored = 0;
                for (PendingRow row : pending) {
                    int rowsAffected = jdbcTemplate.update(INSERT_CHUNK_SQL,
                            UUID.randomUUID(),
                            fileName,
                            fileType,
                            fileHash,
                            fileSize,
                            modified,
                            now,
                            row.text,
                            row.embedding,
                            row.chunkIndex,
                            totalSegments,
                            row.chunkStart,
                            row.chunkEnd,
                            row.metadataJson,
                            nextVersion
                    );

                    if (rowsAffected > 0) {
                        stored++;
                        result.incrementSegmentsStored();
                    }
                }
                return stored;
            });

            Duration docDuration = Duration.between(docStart, Instant.now());
            log.info("✅ Finished database synchronization for file: {} ({} segments stored in {} ms)",
                    fileName, processedSegments, docDuration.toMillis());

            return processedSegments > 0;

        } catch (Exception e) {
            log.error("❌ Failed local ingestion routine for target document: {}", fileName, e);
            return false;
        }
    }

    private boolean isMarkdownFile(Path path) {
        String ext = FilenameUtils.getExtension(path.toString()).toLowerCase();
        return "md".equals(ext) || "markdown".equals(ext);
    }

    private String xtractFileType(String originalName) {
        if (originalName.contains("__")) {
            return originalName.split("__", 2)[0];
        }
        return "General";
    }

    private String xtractFileName(String originalName) {
        if (originalName.contains("__")) {
            return originalName.split("__", 2)[1];
        }
        return originalName;
    }

    private String stripExtension(String fileName) {
        return fileName.replaceFirst("\\.[^.]+$", "");
    }

    private String cleanDocumentText(String text) {
        if (text == null || text.isEmpty()) return "";

        // Centralized TOC + running header/footer strip (never embedded).
        // Line-based only so inline "Version 2.1 policy" / "March 2024" survives.
        try {
            DocNoiseStripper.StripResult stripped = docNoiseStripper.strip(text);
            if (stripped.getTocRemoved() + stripped.getHeaderFooterRemoved() > 0) {
                log.info("Noise strip: removed {} TOC + {} header/footer lines",
                        stripped.getTocRemoved(), stripped.getHeaderFooterRemoved());
            }
            text = stripped.getText();
        } catch (Exception e) {
            log.warn("DocNoiseStripper failed, continuing with raw text: {}", e.getMessage());
        }

        // Line-based footer/boilerplate stripping (conservative: only short footer-style lines).
        // Previous version used inline replaceAll that destroyed substantive dates/versions
        // (e.g. "Version 2.1 policy" or "March 2024 allowances") needed for tail questions.
        String[] lines = text.split("\\n", -1);
        List<String> kept = new ArrayList<>(lines.length);
        for (String line : lines) {
            String t = line.strip();
            // Page footer only when the whole line is a page marker
            if (FOOTER_PAGE.matcher(t).matches()) continue;
            // Version footer only for short footer lines like "Version: 1.2"
            if (FOOTER_VERSION.matcher(t).matches() && t.length() < 40) continue;
            // Copyright/confidential footer only for short footer lines (keep inline content)
            if (t.length() < 120 && FOOTER_COPY.matcher(t).matches()) continue;
            kept.add(line);
        }
        String cleaned = String.join("\n", kept);

        // 5. Normalize broken table rows (OCR damage)
        cleaned = normalizeBrokenTables(cleaned);

        // 6. Blank lines are PARAGRAPH STRUCTURE — the splitter breaks on \n\n.
        // A previous version deleted every blank line here, which silently turned
        // paragraph chunking into dead code (chunks then broke only on headers or
        // arbitrary 1000-char cuts). DocNoiseStripper already collapses 3+ blanks
        // to max 2 upstream; keep the rest.

        return cleaned.trim();
    }

    /**
     * Fix OCR-garbled markdown tables where pipe delimiters are missing.
     * When inside a table context (previous line had |), lines without leading |
     * that contain table-like data get wrapped with | delimiters.
     */
    private String normalizeBrokenTables(String text) {
        String[] lines = text.split("\n", -1);
        List<String> result = new ArrayList<>();
        boolean inTable = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.strip();

            boolean isProperRow = trimmed.startsWith("|") && trimmed.endsWith("|");

            if (isProperRow) {
                inTable = true;
                result.add(line);
            } else if (inTable && isTableContinuationLine(trimmed)) {
                result.add("| " + trimmed + " |");
            } else if (inTable && !trimmed.isEmpty() && looksLikeTableData(trimmed)) {
                result.add("| " + trimmed + " |");
            } else {
                if (inTable && trimmed.isEmpty()) {
                    if (i + 1 < lines.length) {
                        String nextTrimmed = lines[i + 1].strip();
                        if (nextTrimmed.startsWith("|") || looksLikeTableData(nextTrimmed)) {
                            result.add(line);
                            continue;
                        }
                    }
                    inTable = false;
                } else if (inTable && !trimmed.isEmpty()) {
                    inTable = false;
                }
                result.add(line);
            }
        }

        return String.join("\n", result);
    }

    private boolean isTableContinuationLine(String trimmed) {
        if (trimmed.isEmpty()) return false;
        if (TABLE_MONEY.matcher(trimmed).matches()) return true;
        if (TABLE_LEAD_NUM.matcher(trimmed).matches()) return true;
        if (trimmed.contains("|")) return true;
        if (trimmed.length() <= 25) return true;
        if (TABLE_SHORT_TITLE.matcher(trimmed).matches() && trimmed.length() < 80) return true;
        return false;
    }

    private boolean looksLikeTableData(String trimmed) {
        if (trimmed.isEmpty()) return false;
        if (TABLE_GROUPED_NUM.matcher(trimmed).matches()) return true;
        if (TABLE_CURR.matcher(trimmed).matches()) return true;
        if (TABLE_NUM_TITLE.matcher(trimmed).matches()) return true;
        if (trimmed.length() <= 20 && !trimmed.contains(".") && !trimmed.endsWith(":")) return true;
        return false;
    }

    private enum FileProcessingStatus {
        NEW, CHANGED, UNCHANGED
    }

    @lombok.Data
    public static class ProcessingResult {
        private int totalDocuments;
        private int newDocuments;
        private int updatedDocuments;
        private int skippedDocuments;
        private int failedDocuments;
        private int totalSegmentsStored;
        private String error;

        private List<String> newFiles = new ArrayList<>();
        private List<String> updatedFiles = new ArrayList<>();
        private List<String> skippedFiles = new ArrayList<>();
        private Map<String, String> failedFiles = new HashMap<>();

        public void addNewDocument(String fileName) {
            newDocuments++;
            newFiles.add(fileName);
        }

        public void addUpdatedDocument(String fileName) {
            updatedDocuments++;
            updatedFiles.add(fileName);
        }

        public void addSkippedDocument(String fileName) {
            skippedDocuments++;
            skippedFiles.add(fileName);
        }

        public void addFailedDocument(String fileName, String reason) {
            failedDocuments++;
            failedFiles.put(fileName, reason);
        }

        public void incrementSegmentsStored() {
            totalSegmentsStored++;
        }
    }
}