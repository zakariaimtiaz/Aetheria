package com.dis.fshipbot.controller;

import com.dis.fshipbot.ingestion.DocNoiseStripper;
import com.dis.fshipbot.service.IngestionService;
import com.dis.fshipbot.service.RetrievalDiagnosticsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Controller
@RequestMapping("/admin/documents")
public class DocumentToolsController {

    @Value("${docs.directory:E:/DBX/docs/markup}")
    private String targetFolder;

    @Autowired
    private IngestionService ingestionService;

    @Autowired
    private RetrievalDiagnosticsService diagnosticsService;

    @Autowired(required = false)
    private DocNoiseStripper docNoiseStripper;

    @Autowired(required = false)
    private com.dis.fshipbot.repository.AppSettingRepository settingRepository;

    /**
     * DB-ONLY: the row in {@code app_setting} is the single source of truth for
     * {@code app.references.enabled}. No application.properties fallback — a
     * missing row means the default (references OFF). Must stay in sync with
     * {@code ChatService.isReferencesEnabled()}, which reads the same key.
     */
    private static final String REFERENCES_KEY = "app.references.enabled";
    private static final boolean REFERENCES_DEFAULT = false;

    @GetMapping("")
    public String index(Model model) {
        model.addAttribute("activeMenu", "documents");
        return "admin-documents";
    }

    @PostMapping("/convert")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> convertFile(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "No file selected."));
        }

        try {
            // 1. Ensure the output target directory exists
            Path outputPath = Paths.get(targetFolder);
            if (!Files.exists(outputPath)) {
                Files.createDirectories(outputPath);
            }

            // 2. Save the file using the modern NIO Path stream (Zero Null-Safety Issues)
            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Invalid file name."));
            }

            Path tempFile = Files.createTempFile("upload_", "_" + originalFilename);
            try (var is = file.getInputStream()) {
                Files.copy(is, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            log.info("Processing conversion for: {}", originalFilename);

            // 3. Pass the absolute string path directly to the process builder
            ProcessBuilder pb = new ProcessBuilder("markitdown", tempFile.toAbsolutePath().toString());
            pb.redirectErrorStream(false);
            Process process = pb.start();

            // 4. Capture the generated markdown payload (stdout only — stderr warnings filtered out)
            String markdownResult;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                markdownResult = reader.lines().collect(Collectors.joining("\n"));
            }

            // Drain stderr to avoid process hanging (warnings like ffmpeg are discarded)
            try (BufferedReader errReader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                errReader.lines().forEach(line -> log.debug("markitdown stderr: {}", line));
            }

            int exitCode = process.waitFor();
            
            // Clean up temporary upload file
            Files.deleteIfExists(tempFile);

            if (exitCode == 0) {
                // 5. Strip TOC + running headers/footers so the saved .md is clean
                // at rest (same centralized stripper the ingestion pipeline uses).
                String cleanedMarkdown = markdownResult;
                int tocRemoved = 0;
                int hfRemoved = 0;
                if (docNoiseStripper != null) {
                    try {
                        DocNoiseStripper.StripResult stripped =
                                docNoiseStripper.strip(markdownResult);
                        cleanedMarkdown = stripped.getText();
                        tocRemoved = stripped.getTocRemoved();
                        hfRemoved = stripped.getHeaderFooterRemoved();
                    } catch (Exception e) {
                        log.warn("Noise stripping failed, saving raw markdown: {}", e.getMessage());
                    }
                }
                String mdFileName = originalFilename.substring(0, originalFilename.lastIndexOf(".")) + ".md";
                Path finalMarkdownPath = outputPath.resolve(mdFileName);
                Files.writeString(finalMarkdownPath, cleanedMarkdown, StandardCharsets.UTF_8);

                log.info("Successfully converted and saved to: {} (TOC lines removed: {}, header/footer removed: {})",
                        finalMarkdownPath.toAbsolutePath(), tocRemoved, hfRemoved);
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", originalFilename + " converted to " + mdFileName
                                + " (removed " + tocRemoved + " TOC / "
                                + hfRemoved + " header-footer lines)"
                ));
            } else {
                return ResponseEntity.internalServerError().body(Map.of(
                        "success", false, "message", "MarkItDown conversion failed."
                ));
            }

        } catch (Exception e) {
            log.error("Error during file processing", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false, "message", "Error: " + e.getMessage()
            ));
        }
    }

    @PostMapping("/upload-md")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> uploadMarkdown(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "No file selected."));
        }

        String originalFilename = file.getOriginalFilename();
        String lower = originalFilename == null ? "" : originalFilename.toLowerCase(Locale.ROOT);
        boolean markdown = lower.endsWith(".md") || lower.endsWith(".markdown");
        boolean excel = lower.endsWith(".xlsx") || lower.endsWith(".xls");
        if (originalFilename == null || (!markdown && !excel)) {
            return ResponseEntity.badRequest().body(Map.of("success", false,
                    "message", "Only .md, .markdown, .xlsx and .xls files are allowed."));
        }

        try {
            Path outputPath = Paths.get(targetFolder);
            if (!Files.exists(outputPath)) {
                Files.createDirectories(outputPath);
            }

            Path finalPath = outputPath.resolve(originalFilename);
            try (var is = file.getInputStream()) {
                Files.copy(is, finalPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            log.info("File uploaded directly (.md ingested as markdown, .xlsx/.xls as Excel tables): {}",
                    finalPath.toAbsolutePath());
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", originalFilename + " saved to " + finalPath.toAbsolutePath()
            ));

        } catch (Exception e) {
            log.error("Failed to upload markdown file", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false, "message", "Upload failed: " + e.getMessage()
            ));
        }
    }

    @PostMapping("/ingest")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> triggerIngestion(
            @RequestParam(value = "force", required = false, defaultValue = "false") boolean force) {
        log.info("Ingestion triggered from UI (force={})", force);
        String result = ingestionService.runIngestion(force);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", result
        ));
    }

    @GetMapping("/ingest/status")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> ingestionStatus() {
        return ResponseEntity.ok(Map.of(
                "running", ingestionService.isIngestionRunning()
        ));
    }

    // ---- Chat display settings (References badges) ----

    @GetMapping("/api/display")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getDisplay() {
        boolean enabled = REFERENCES_DEFAULT;
        // "db" when the row exists, "default" when it does not — lets the page
        // tell the admin that the toggle has never been saved, so flipping it is
        // what actually creates the row.
        String source = "default";
        if (settingRepository != null) {
            try {
                String v = settingRepository.getValue(REFERENCES_KEY, null);
                if (v != null) {
                    enabled = !"false".equalsIgnoreCase(v.trim());
                    source = "db";
                }
            } catch (Exception e) {
                log.warn("display GET failed, using default {}: {}", REFERENCES_DEFAULT, e.getMessage());
            }
        }
        return ResponseEntity.ok(Map.of("referencesEnabled", enabled, "source", source));
    }

    @PostMapping("/api/display")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> setDisplay(@RequestBody Map<String, Object> body) {
        if (settingRepository == null) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false, "message", "Settings store unavailable."));
        }
        Object raw = body.get("referencesEnabled");
        if (raw == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "message", "Missing 'referencesEnabled' field."));
        }
        boolean enabled = "true".equalsIgnoreCase(String.valueOf(raw).trim());
        try {
            settingRepository.upsert(REFERENCES_KEY, enabled ? "true" : "false", "display");
            log.info("{} set to {} from Document Tools page", REFERENCES_KEY, enabled);
            return ResponseEntity.ok(Map.of(
                    "success", true, "referencesEnabled", enabled, "source", "db"));
        } catch (Exception e) {
            log.error("display POST failed", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false, "message", "Save failed: " + e.getMessage()));
        }
    }

    // ---- Retrieval diagnostics (vector index / corpus health) ----

    @GetMapping("/api/diagnostics/retrieval")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> retrievalDiagnostics() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("corpus", diagnosticsService.corpus());
        out.put("index", diagnosticsService.index());
        return ResponseEntity.ok(out);
    }

    /**
     * Compares hits at ivfflat.probes=1 (the broken default) against the
     * configured probe count, proving whether the index was hiding chunks.
     * Pass a raw 384-dim vector literal, e.g. from a debugger or psql.
     */
    @GetMapping("/api/diagnostics/recall")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> recallProbe(
            @RequestParam("vector") String vector,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return ResponseEntity.ok(diagnosticsService.recallProbe(vector, limit));
    }
}
