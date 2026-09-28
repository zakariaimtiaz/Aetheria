package com.dis.fshipbot.ingestion;

import com.dis.fshipbot.repository.DocumentRepository;
import com.dis.fshipbot.util.ContentHashUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * Deterministic Excel ingestion — no LLM calls, no quota burn.
 *
 * <p>Each sheet is rendered to a markdown table ({@code ## File — Sheet} header
 * + summary line + pipe table) and fed through {@link DocumentSplitter}, so
 * Excel chunks get the exact same treatment as markdown tables: header
 * repetition per row-group, {@code content_type=table} (tableBoost applies),
 * {@code section_header} (per-section diversity works), and the BGE 512-token
 * budget clamp. Sheets are split independently with a running chunk offset so
 * {@code chunk_index} is unique per file.
 *
 * <p>Atomicity mirrors {@link DocumentProcessor}: all batches embed OUTSIDE any
 * transaction, then old rows are deleted and new rows inserted in ONE
 * TransactionTemplate swap. A mid-file failure leaves the old data intact.
 */
@Slf4j
@Component
public class ExcelContentProcessor {

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private DocumentSplitter documentSplitter;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private ContentHashUtil contentHashUtil;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Value("${docs.directory}")
    private String docsDirectory;

    @Value("${excel.batch-size:20}")
    private int batchSize;

    @Value("${excel.max-rows-per-sheet:5000}")
    private int maxRowsPerSheet;

    @Value("${app.ingestion.excel-enabled:true}")
    private boolean excelEnabled;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DataFormatter formatter = new DataFormatter();

    /** Fallbacks when instantiated without Spring (unit tests): @Value ints default to 0. */
    private static final int DEFAULT_BATCH_SIZE = 20;
    private static final int DEFAULT_MAX_ROWS = 5000;

    private int effectiveBatchSize() {
        return batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
    }

    private int effectiveMaxRows() {
        return maxRowsPerSheet > 0 ? maxRowsPerSheet : DEFAULT_MAX_ROWS;
    }

    private static final String INSERT_CHUNK_SQL =
            "INSERT INTO knowledge_base (" +
            "embedding_id, file_name, file_type, file_hash, file_size, " +
            "last_modified, processed_date, text, embedding, chunk_index, " +
            "total_chunks, chunk_start, chunk_end, metadata, \"version\") " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::vector, ?, ?, ?, ?, ?::jsonb, ?)";

    /**
     * @param forceAll when true, skip the content-hash check and re-embed EVERY
     *                 workbook. Required after any {@link DocumentSplitter} change
     *                 — Excel is chunked by the same splitter as markdown, so a
     *                 chunker change is otherwise invisible here (file bytes are
     *                 unchanged, the hash matches, the file is skipped).
     */
    public ProcessingResult processAllDocuments(boolean forceAll) {
        ProcessingResult result = new ProcessingResult();

        if (!excelEnabled) {
            log.info("Excel ingestion disabled (app.ingestion.excel-enabled=false) — skipping.");
            return result;
        }

        try {
            File folder = new File(docsDirectory);
            if (!folder.exists() || !folder.isDirectory()) {
                log.warn("Excel folder not found: {}", docsDirectory);
                return result;
            }

            File[] files = folder.listFiles((dir, name) -> {
                String lower = name.toLowerCase(Locale.ROOT);
                if (name.startsWith("~$")) return false; // Excel lock file
                return lower.endsWith(".xlsx") || lower.endsWith(".xls");
            });

            if (files == null || files.length == 0) {
                log.info("No Excel files found in {}.", docsDirectory);
                return result;
            }

            result.setTotalDocuments(files.length);
            log.info("Found {} Excel file(s) to process", files.length);
            if (forceAll) {
                log.warn("FORCING full re-embed of {} Excel file(s), content hash check skipped",
                        files.length);
            }

            for (File file : files) {
                try {
                    Boolean updated = processSingleFile(file, result, forceAll);
                    if (updated == null) {
                        result.incrementSkippedDocuments();
                    } else if (updated) {
                        result.incrementUpdatedDocuments();
                        result.addUpdatedFile(file.getName());
                    } else {
                        result.incrementNewDocuments();
                        result.addNewFile(file.getName());
                    }
                } catch (Exception e) {
                    log.error("Failed to process Excel: {}", file.getName(), e);
                    result.incrementFailedDocuments();
                    result.addFailedFile(file.getName(), e.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("Excel ingestion failed", e);
            result.setError(e.getMessage());
        }

        return result;
    }

    /**
     * @return null when unchanged (skipped), true when an existing file was
     *         re-indexed, false when brand new.
     */
    Boolean processSingleFile(File file, ProcessingResult result, boolean forceAll) throws Exception {
        String fileName = file.getName();
        String fileHash = contentHashUtil.calculateFileHash(file);
        long fileSize = Files.size(file.toPath());
        long lastModified = Files.getLastModifiedTime(file.toPath()).toMillis();

        DocumentRepository.FileInfo latestFile = documentRepository.getLatestFileInfo(fileName);
        if (!forceAll && latestFile != null && fileHash.equals(latestFile.getFileHash())) {
            log.info("Skipping {} - content hash is unmodified", fileName);
            return null;
        }
        // With forceAll an existing file is re-indexed, so report it as an update
        // (not "new") — otherwise the summary understates what was rebuilt.
        boolean isUpdate = latestFile != null;

        // Embed everything BEFORE touching the DB (ONNX is slow; old rows stay
        // live until the swap, and any failure here retries cleanly next run).
        List<PendingRow> pending = new ArrayList<>();
        try (InputStream in = new FileInputStream(file);
             Workbook workbook = WorkbookFactory.create(in)) {
            for (Sheet sheet : workbook) {
                Optional<String> markdown = sheetToMarkdown(sheet, fileName);
                if (markdown.isEmpty()) {
                    continue;
                }
                Metadata baseMeta = new Metadata();
                baseMeta.put("file_name", fileName);
                baseMeta.put("file_type", "excel");
                baseMeta.put("sheet_name", sheet.getSheetName());
                baseMeta.put("ingestion_source", "excel");
                List<TextSegment> segments =
                        documentSplitter.split(Document.from(markdown.get(), baseMeta), pending.size());
                String stamped = Instant.now().toString();
                for (TextSegment segment : segments) {
                    segment.metadata().put("processed_timestamp", stamped);
                    PendingRow row = new PendingRow();
                    row.text = segment.text();
                    row.metadataJson = objectMapper.writeValueAsString(segment.metadata().asMap());
                    row.chunkIndex = Integer.parseInt(segment.metadata().get("chunk_index"));
                    row.chunkStart = Integer.parseInt(segment.metadata().get("chunk_start"));
                    row.chunkEnd = Integer.parseInt(segment.metadata().get("chunk_end"));
                    pending.add(row);
                }
                log.info("Sheet '{}' -> {} segments", sheet.getSheetName(), segments.size());
            }
        }

        if (pending.isEmpty()) {
            log.warn("No indexable data in Excel file: {}", fileName);
            return null;
        }

        int nextVersion = documentRepository.getNextVersion(fileName);
        Timestamp now = Timestamp.from(Instant.now());
        Timestamp modified = Timestamp.from(Instant.ofEpochMilli(lastModified));
        int stored = new TransactionTemplate(transactionManager).execute(txStatus -> {
            documentRepository.removeByFileName(fileName);
            int step = effectiveBatchSize();
            int count = 0;
            for (int i = 0; i < pending.size(); i += step) {
                List<Embedding> embeddings = embeddingModel
                        .embedAll(pendingRowsToSegments(pending, i, Math.min(i + step, pending.size())))
                        .content();
                for (int j = 0; j < embeddings.size(); j++) {
                    PendingRow row = pending.get(i + j);
                    int rowsAffected = jdbcTemplate.update(INSERT_CHUNK_SQL,
                            UUID.randomUUID(),
                            fileName,
                            "excel",
                            fileHash,
                            fileSize,
                            modified,
                            now,
                            row.text,
                            embeddings.get(j).vector(),
                            row.chunkIndex,
                            pending.size(),
                            row.chunkStart,
                            row.chunkEnd,
                            row.metadataJson,
                            nextVersion);
                    if (rowsAffected > 0) {
                        count++;
                        result.incrementSegmentsStored();
                    }
                }
            }
            return count;
        });

        log.info("Excel {} stored: {} ({} segments)", isUpdate ? "updated" : "ingested", fileName, stored);
        return isUpdate;
    }

    private List<TextSegment> pendingRowsToSegments(List<PendingRow> pending, int from, int to) {
        List<TextSegment> out = new ArrayList<>(to - from);
        for (int i = from; i < to; i++) {
            out.add(TextSegment.from(pending.get(i).text));
        }
        return out;
    }

    /**
     * Render one sheet as a markdown table with a section header + summary line.
     * Returns empty when the sheet has no headers or no data rows.
     */
    Optional<String> sheetToMarkdown(Sheet sheet, String fileName) {
        if (sheet == null) return Optional.empty();
        String sheetName = sheet.getSheetName();

        Row headerRow = firstNonEmptyRow(sheet, 5);
        if (headerRow == null) {
            log.info("Sheet '{}' has no header row — skipping", sheetName);
            return Optional.empty();
        }
        List<String> headers = extractHeaders(headerRow);
        if (headers.isEmpty()) {
            log.info("Sheet '{}' has empty headers — skipping", sheetName);
            return Optional.empty();
        }

        List<List<String>> dataRows = new ArrayList<>();
        int firstDataIdx = headerRow.getRowNum() + 1;
        int maxRows = effectiveMaxRows();
        int lastIdx = Math.min(sheet.getLastRowNum(), firstDataIdx + maxRows - 1);
        for (int r = firstDataIdx; r <= lastIdx; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            List<String> cells = new ArrayList<>(headers.size());
            boolean hasData = false;
            for (int c = 0; c < headers.size(); c++) {
                // RETURN_BLANK_AS_NULL: never mutate the workbook (CREATE_NULL_AS_BLANK
                // inserts blank cell objects into sparse sheets and blows up memory).
                Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                String value = cellValue(cell).replace("\n", " ").replace("\r", " ").strip();
                cells.add(value);
                if (!value.isEmpty()) hasData = true;
            }
            if (hasData) dataRows.add(cells);
        }
        if (dataRows.isEmpty()) {
            log.info("Sheet '{}' has headers but no data rows — skipping", sheetName);
            return Optional.empty();
        }
        if (sheet.getLastRowNum() > lastIdx) {
            log.warn("Sheet '{}' truncated to {} rows (excel.max-rows-per-sheet={})",
                    sheetName, maxRows, maxRows);
        }

        StringBuilder md = new StringBuilder();
        md.append("## ").append(fileName).append(" — ").append(sheetName).append("\n");
        md.append(dataRows.size()).append(" rows x ").append(headers.size())
          .append(" columns: ").append(String.join(", ", headers)).append("\n");
        md.append("| ").append(String.join(" | ", escapeAll(headers))).append(" |\n");
        md.append("| ").append(String.join(" | ", Collections.nCopies(headers.size(), "---"))).append(" |\n");
        for (List<String> row : dataRows) {
            List<String> padded = new ArrayList<>(row);
            while (padded.size() < headers.size()) padded.add("");
            md.append("| ").append(String.join(" | ", escapeAll(padded))).append(" |\n");
        }
        return Optional.of(md.toString());
    }

    private Row firstNonEmptyRow(Sheet sheet, int scanLimit) {
        int last = Math.min(sheet.getLastRowNum(), scanLimit - 1);
        for (int r = 0; r <= last; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (Cell cell : row) {
                if (!cellValue(cell).isEmpty()) return row;
            }
        }
        return null;
    }

    private List<String> extractHeaders(Row headerRow) {
        List<String> headers = new ArrayList<>();
        int idx = 0;
        for (Cell cell : headerRow) {
            String value = cellValue(cell).strip();
            headers.add(value.isEmpty() ? "Column " + (idx + 1) : value);
            idx++;
        }
        // Trim trailing auto-generated columns (ragged header rows)
        while (!headers.isEmpty() && headers.get(headers.size() - 1).startsWith("Column ")) {
            headers.remove(headers.size() - 1);
        }
        return headers;
    }

    private List<String> escapeAll(List<String> cells) {
        List<String> out = new ArrayList<>(cells.size());
        for (String c : cells) {
            // A literal pipe would split the markdown column — escape it so the
            // table structure (and header detection) survives. Content stays readable.
            out.add(c.replace("|", "\\|"));
        }
        return out;
    }

    private String cellValue(Cell cell) {
        if (cell == null) return "";
        // DataFormatter handles numerics, dates, booleans and cached formula
        // results uniformly — no hand-rolled type switch to rot.
        try {
            return formatter.formatCellValue(cell).strip();
        } catch (Exception e) {
            return "";
        }
    }

    private static final class PendingRow {
        String text;
        String metadataJson;
        int chunkIndex;
        int chunkStart;
        int chunkEnd;
    }

    // Inner classes
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
        private Map<String, String> failedFiles = new HashMap<>();

        public void incrementNewDocuments() { newDocuments++; }
        public void incrementUpdatedDocuments() { updatedDocuments++; }
        public void incrementSkippedDocuments() { skippedDocuments++; }
        public void incrementFailedDocuments() { failedDocuments++; }
        public void incrementSegmentsStored() { totalSegmentsStored++; }

        public void addNewFile(String fileName) {
            newFiles.add(fileName);
        }

        public void addUpdatedFile(String fileName) {
            updatedFiles.add(fileName);
        }

        public void addFailedFile(String fileName, String reason) {
            failedFiles.put(fileName, reason);
        }
    }
}
