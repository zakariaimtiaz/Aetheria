package com.dis.fshipbot.service;

import com.dis.fshipbot.ingestion.DocumentProcessor;
import com.dis.fshipbot.ingestion.ExcelContentProcessor;
import com.dis.fshipbot.ingestion.WebContentProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class IngestionService implements CommandLineRunner {

    @Autowired
    private DocumentProcessor documentProcessor;

    @Autowired
    private ExcelContentProcessor excelContentProcessor;

    @Autowired
    private WebContentProcessor webContentProcessor;

    @Value("${app.ingestion.run-on-startup:false}")
    private boolean runIngestionOnStartup;

    private volatile boolean ingestionRunning = false;

    @Override
    public void run(String... args) throws Exception {
        log.info("=".repeat(80));
        log.info("🚀 FRIENDSHIP NGO DOCUMENT INGESTION CHECK");
        log.info("=".repeat(80));

        if (runIngestionOnStartup) {
            runIngestion();
        } else {
            log.info("Skipping document ingestion on startup.");
        }
    }

    /**
     * Run document ingestion on demand (called from UI button).
     * Returns a summary string for the API response.
     */
    public synchronized String runIngestion() {
        return runIngestion(false);
    }

    /**
     * @param forceFullRebuild when true, re-embed EVERY source (local markdown,
     *        Excel workbooks and web.urls), skipping the content hash check. This
     *        is the only way to force a full re-index — it backs the "Force full
     *        re-embed" checkbox on the Document Tools page. Needed after any
     *        chunker / stripper / embedding-model change, because the hash only
     *        covers file CONTENT, which such changes do not alter.
     */
    public synchronized String runIngestion(boolean forceFullRebuild) {
        if (ingestionRunning) {
            log.warn("Ingestion already in progress — skipping");
            return "Ingestion already in progress.";
        }

        ingestionRunning = true;
        log.info("=".repeat(80));
        log.info("🔄 DOCUMENT INGESTION STARTED (force={})", forceFullRebuild);
        log.info("=".repeat(80));

        try {
            StringBuilder summary = new StringBuilder();

            DocumentProcessor.ProcessingResult docResult = documentProcessor
                    .processAllDocuments(forceFullRebuild);
            logDocumentResult(docResult, "DOCUMENTS");
            summary.append(String.format("Documents: %d total, %d new, %d updated, %d skipped, %d failed, %d segments stored",
                    docResult.getTotalDocuments(), docResult.getNewDocuments(),
                    docResult.getUpdatedDocuments(), docResult.getSkippedDocuments(),
                    docResult.getFailedDocuments(), docResult.getTotalSegmentsStored()));

            // Excel workbooks (.xlsx/.xls) in the same folder — deterministic
            // sheet-to-markdown ingestion (no LLM quota). No-op when no files.
            // forceFullRebuild is threaded through here too: Excel is chunked by
            // the same DocumentSplitter as markdown, so leaving it incremental
            // would silently keep stale Excel chunks after a chunker change while
            // the admin believed the whole knowledge base had been re-embedded.
            ExcelContentProcessor.ProcessingResult excelResult =
                    excelContentProcessor.processAllDocuments(forceFullRebuild);
            logExcelResult(excelResult, "EXCEL");
            summary.append(String.format(" | Excel: %d total, %d new, %d updated, %d skipped, %d failed, %d segments stored",
                    excelResult.getTotalDocuments(), excelResult.getNewDocuments(),
                    excelResult.getUpdatedDocuments(), excelResult.getSkippedDocuments(),
                    excelResult.getFailedDocuments(), excelResult.getTotalSegmentsStored()));

            // Web pages configured in web.urls. Also force-aware for the same
            // reason as Excel: a chunker change does not alter page bytes, so
            // the content hash would match and every page would be skipped.
            // Must never fail the run — a dead website is not a failed rebuild.
            try {
                WebContentProcessor.ProcessingResult webResult =
                        webContentProcessor.processConfiguredUrls(forceFullRebuild);
                logWebResult(webResult, "WEB");
                summary.append(String.format(" | Web: %d URLs, %d new, %d updated, %d skipped, %d failed, %d segments stored",
                        webResult.getTotalDocuments(), webResult.getNewDocuments(),
                        webResult.getUpdatedDocuments(), webResult.getSkippedDocuments(),
                        webResult.getFailedDocuments(), webResult.getTotalSegmentsStored()));
            } catch (Exception e) {
                log.error("Web ingestion failed (continuing — other sources are unaffected)", e);
                summary.append(" | Web: FAILED (").append(e.getMessage()).append(")");
            }

            if (forceFullRebuild) {
                summary.append(" | FORCED full re-embed: content hash check skipped for .md, .xlsx/.xls and web.urls (every source re-embedded)");
            }
            return summary.toString();
        } catch (Exception e) {
            log.error("Ingestion failed", e);
            return "Ingestion failed: " + e.getMessage();
        } finally {
            ingestionRunning = false;
        }
    }

    public boolean isIngestionRunning() {
        return ingestionRunning;
    }

    private void logDocumentResult(DocumentProcessor.ProcessingResult result, String type) {
        log.info("=".repeat(80));
        log.info("📊 {} INGESTION RESULTS:", type);
        log.info("   - Total: {}", result.getTotalDocuments());
        log.info("   - New: {}", result.getNewDocuments());
        log.info("   - Updated: {}", result.getUpdatedDocuments());
        log.info("   - Skipped: {}", result.getSkippedDocuments());
        log.info("   - Failed: {}", result.getFailedDocuments());
        log.info("   - Segments Stored: {}", result.getTotalSegmentsStored());
        log.info("=".repeat(80));
    }

    private void logExcelResult(ExcelContentProcessor.ProcessingResult result, String type) {
        log.info("=".repeat(80));
        log.info("📊 {} INGESTION RESULTS:", type);
        log.info("   - Total: {}", result.getTotalDocuments());
        log.info("   - New: {}", result.getNewDocuments());
        log.info("   - Updated: {}", result.getUpdatedDocuments());
        log.info("   - Skipped: {}", result.getSkippedDocuments());
        log.info("   - Failed: {}", result.getFailedDocuments());
        log.info("   - Segments Stored: {}", result.getTotalSegmentsStored());
        log.info("=".repeat(80));
    }

    private void logWebResult(WebContentProcessor.ProcessingResult result, String type) {
        log.info("=".repeat(80));
        log.info("🌐 {} INGESTION RESULTS:", type);
        log.info("   - Total URLs: {}", result.getTotalDocuments());
        log.info("   - New: {}", result.getNewUrls());
        log.info("   - Updated: {}", result.getUpdatedUrls());
        log.info("   - Skipped: {}", result.getSkippedUrls());
        log.info("   - Failed: {}", result.getFailedUrls());
        log.info("   - Segments Stored: {}", result.getTotalSegmentsStored());
        if (!result.getFailedFiles().isEmpty()) {
            log.warn("   - Failure detail: {}", result.getFailedFiles());
        }
        log.info("=".repeat(80));
    }

}