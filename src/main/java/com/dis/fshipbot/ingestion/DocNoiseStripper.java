package com.dis.fshipbot.ingestion;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Centralized TOC / running header-footer / boilerplate stripper.
 *
 * <p>Runs BEFORE splitting + embedding so noise never enters vectors or the
 * hybrid lexical index. Provenance (page numbers, doc version) belongs in
 * metadata JSONB, not in embedded text.
 *
 * <p>Line-based only — never inline replace, so substantive inline content
 * like "Version 2.1 policy" or "March 2024 allowances" survives.
 */
@Slf4j
@Component
public class DocNoiseStripper {

    private static final Pattern TOC_DOTTED = Pattern.compile(".*\\.{2,}\\s*\\d+\\s*$");
    private static final Pattern TOC_DASHED = Pattern.compile(".*-{2,}\\s*\\d+\\s*$");
    private static final Pattern TOC_GAPPED = Pattern.compile(".*\\s{2,}\\d+\\s*$");
    private static final Pattern TOC_HEADING = Pattern.compile("(?i)^\\s*#{0,4}\\s*(table of contents|contents|index)\\s*$");

    // Deterministic footer/header lines (whole-line match only).
    private static final Pattern FOOTER_PAGE = Pattern.compile("(?i)^(Page|P\\.)\\s*\\d+\\s*(of|/\\s*\\d+)?\\s*$");
    private static final Pattern FOOTER_VERSION = Pattern.compile("(?i)^.{0,10}(Version|Ver)\\s*[:.]?\\s*\\d+\\.\\d+\\s*$");
    private static final Pattern FOOTER_COPY = Pattern.compile("(?i)^(©.*|Copyright.*|Confidential.*|All rights reserved.*)$");
    private static final Pattern HEADER_ISSUED = Pattern.compile("(?i)^(Date/Month Issued:.*|This Document Is confidential.*|Express written permission.*|Friendship Human Reso.*|&\\s*Process.*Procedures.*Manual.*)$");
    private static final Pattern PAGE_OF = Pattern.compile("(?i).*Page \\d+ of \\d+.*|.*Version:\\s*\\d+.*");

    // Numbered TOC entry with trailing page number: "4.2 Annual Leave 12" or "4.2.1 Sick Leave ... 15".
    private static final Pattern NUMBERED_TOC_ENTRY = Pattern.compile("^\\d+(\\.\\d+){1,3}\\.?\\s+[A-Za-z].*\\s+\\d+\\s*$");

    private static final Pattern TOC_TITLE = Pattern.compile("(?i)^(chapter|section|part|annex|appendix)\\s+\\d+.*");

    /**
     * Strip noise. Returns cleaned text; logs counts for ingestion observability.
     */
    public StripResult strip(String text) {
        if (text == null || text.isEmpty()) {
            return new StripResult("", 0, 0);
        }
        String[] lines = text.split("\\n", -1);

        // Pass 1: frequency-based running header/footer detection.
        // Same short normalized line on >=3 "pages" (45-line proxy, or \f split) = running chrome.
        Map<String, Integer> freq = new HashMap<>();
        for (String line : lines) {
            String n = normalize(line);
            if (n.isEmpty() || n.length() > 120) {
                continue;
            }
            freq.put(n, freq.getOrDefault(n, 0) + 1);
        }

        List<String> kept = new ArrayList<>(lines.length);
        int tocRemoved = 0;
        int hfRemoved = 0;
        boolean inToc = false;
        int nonTocStreak = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.strip();

            // --- Deterministic header/footer FIRST (independent of TOC mode:
            // running chrome right after a TOC block must still die) ---
            if (FOOTER_PAGE.matcher(trimmed).matches()) {
                hfRemoved++;
                continue;
            }
            if (trimmed.length() < 40 && FOOTER_VERSION.matcher(trimmed).matches()) {
                hfRemoved++;
                continue;
            }
            if (trimmed.length() < 120 && FOOTER_COPY.matcher(trimmed).matches()) {
                hfRemoved++;
                continue;
            }
            if (HEADER_ISSUED.matcher(trimmed).matches() || PAGE_OF.matcher(trimmed).matches()) {
                hfRemoved++;
                continue;
            }

            // --- TOC heading block ---
            if (TOC_HEADING.matcher(trimmed).matches()) {
                inToc = true;
                nonTocStreak = 0;
                tocRemoved++;
                continue;
            }
            if (inToc) {
                if (trimmed.isEmpty()) {
                    kept.add(line);
                    continue;
                }
                if (isTocLine(trimmed) || isTocTitleLine(trimmed)) {
                    tocRemoved++;
                    continue;
                }
                if (trimmed.startsWith("#")) {
                    inToc = false;
                    kept.add(line);
                    continue;
                }
                nonTocStreak++;
                if (nonTocStreak >= 5) {
                    inToc = false;
                    kept.add(line);
                } else {
                    kept.add(line);
                }
                continue;
            }
            // Dotted leaders in doc head (first 120 lines) without explicit heading.
            if (i < 120 && isTocLine(trimmed)) {
                tocRemoved++;
                continue;
            }
            if (NUMBERED_TOC_ENTRY.matcher(trimmed).matches() && trimmed.length() < 180) {
                tocRemoved++;
                continue;
            }

            // --- Frequency-based running chrome ---
            String n = normalize(line);
            if (!n.isEmpty() && n.length() <= 120 && freq.getOrDefault(n, 0) >= 3
                    && looksLikeChrome(trimmed)) {
                hfRemoved++;
                continue;
            }

            kept.add(line);
        }

        String cleaned = String.join("\n", kept);
        // Collapse 3+ blank lines to max 2 (keeps paragraph structure for splitter).
        cleaned = cleaned.replaceAll("(?m)^[ \\t]*\\r?\\n(?:[ \\t]*\\r?\\n){2,}", "\n\n").trim();
        return new StripResult(cleaned, tocRemoved, hfRemoved);
    }

    private boolean isTocLine(String trimmed) {
        if (trimmed.isEmpty() || trimmed.length() > 220) {
            return false;
        }
        if (!trimmed.matches(".*\\d+\\s*$")) {
            return false;
        }
        if (TOC_DOTTED.matcher(trimmed).matches()) {
            return true;
        }
        if (TOC_DASHED.matcher(trimmed).matches()) {
            return true;
        }
        if (trimmed.length() < 180 && trimmed.matches(".*[A-Za-z].*")
                && TOC_GAPPED.matcher(trimmed).matches() && trimmed.length() > 20) {
            // Avoid "Total 5000" style lines: require letter content + length.
            // Conservative — numbered entries handled separately.
            return true;
        }
        return false;
    }

    private boolean isTocTitleLine(String trimmed) {
        if (trimmed.length() > 120) {
            return false;
        }
        return TOC_TITLE.matcher(trimmed).matches()
                || trimmed.matches("^\\d+(\\.\\d+)*\\s+[A-Z].*");
    }

    private boolean looksLikeChrome(String trimmed) {
        String lower = trimmed.toLowerCase();
        // Only drop lines that look like chrome, never substantive sentences.
        if (trimmed.length() < 8) {
            return false;
        }
        return lower.contains("friendship")
                || lower.contains("human resource")
                || lower.contains("confidential")
                || lower.contains("all rights reserved")
                || lower.contains("page ")
                || lower.matches(".*version\\s*[:.]?\\s*\\d+.*")
                || lower.matches(".*date.*issued.*")
                || trimmed.matches("^[A-Z][A-Za-z\\s&\\-]{4,60}$");
    }

    private String normalize(String line) {
        return line.strip().toLowerCase().replaceAll("\\s+", " ");
    }

    public static class StripResult {
        private final String text;
        private final int tocRemoved;
        private final int headerFooterRemoved;

        public StripResult(String text, int tocRemoved, int headerFooterRemoved) {
            this.text = text;
            this.tocRemoved = tocRemoved;
            this.headerFooterRemoved = headerFooterRemoved;
        }

        public String getText() {
            return text;
        }

        public int getTocRemoved() {
            return tocRemoved;
        }

        public int getHeaderFooterRemoved() {
            return headerFooterRemoved;
        }
    }
}
