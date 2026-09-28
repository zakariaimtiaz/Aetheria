package com.dis.fshipbot.ingestion;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class DocumentSplitter {

    @Value("${docs.chunk-size:1000}")
    private int chunkSizeChars;

    @Value("${docs.chunk-overlap:200}")
    private int overlapChars;

    @Value("${docs.table-max-chars:2500}")
    private int tableMaxChars;

    @Value("${docs.merge-adjacent-tables:true}")
    private boolean mergeAdjacentTables;

    /**
     * Hard ceiling for any single table chunk. BGE-small-en-v1.5 truncates past
     * 512 tokens (~4 chars/token incl. section prefix + repeated header), so a
     * larger budget only pretends to embed tail rows. The configured
     * docs.table-max-chars is clamped to this (see tableBudgetOf).
     */
    private static final int MAX_TABLE_BUDGET_CHARS = 1800;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DocNoiseStripper docNoiseStripper;

    private static final Pattern TABLE_ROW = Pattern.compile("(?m)^\\|.*\\|\\s*$");
    // Matches the table separator line (| --- | --- |)
    private static final Pattern TABLE_SEP = Pattern.compile("(?m)^\\|[\\s\\-:|]+\\|\\s*$");
    // Matches repeated copyright/boilerplate that appears on every PDF page
    private static final Pattern BOILERPLATE = Pattern.compile(
            "(?i)(form without the express written permission of Friendship\\." +
            "|Friendship Human Reso" +
            "|All rights reserved" +
            "|©\\s*\\d{4}" +
            "|Date/Month Issued:" +
            "|This Document Is confidential" +
            "|Page \\d+ of \\d+" +
            "|Version:\\s*\\d+" +
            // Running page headers of the HR manual (standalone lines, every page)
            "|^[ \t]*Friendship Human Resources Policies[ \t]*$" +
            "|^[ \t]*& Process/Procedures Manual[ \t]*$)",
            Pattern.MULTILINE);

    // TOC: dotted/dashed leaders with trailing page numbers (e.g. "Chapter 26 .... 145")
    private static final Pattern TOC_DOTTED = Pattern.compile(".*\\.{2,}\\s*\\d+\\s*$");
    private static final Pattern TOC_DASHED = Pattern.compile(".*-{2,}\\s*\\d+\\s*$");
    private static final Pattern TOC_GAPPED = Pattern.compile(".*\\s{2,}\\d+\\s*$");
    private static final Pattern TOC_HEADING = Pattern.compile("(?i)^\\s*#{0,4}\\s*(table of contents|contents|index)\\s*$");
    private static final Pattern MD_HEADER = Pattern.compile("(?m)^(#{1,4}\\s+.*)$");
    private static final Pattern HEADER_LINE = Pattern.compile("^#{1,4}\\s+(.*)$");
    // Numbered policy headings ("4.2 Annual Leave", "4.10 Absence without Leave").
    // The HR manual uses these instead of markdown '#', so without them chunks break
    // mid-section and section_header stays blank (killing per-section diversity).
    private static final Pattern NUMBERED_SECTION = Pattern.compile("^(\\d{1,2}\\.\\d{1,2})\\.?\\s+([A-Za-z].*)$");
    private static final Pattern NUMBERED_SUB = Pattern.compile("^(\\d{1,2}\\.\\d{1,2}\\.\\d{1,3})\\.?(\\s+.*)?$");

    @PostConstruct
    public void init() {
        log.info("Splitter: chunk={} chars, overlap={} chars (table-aware)", chunkSizeChars, overlapChars);
    }

    public List<TextSegment> split(Document document) {
        return split(document, 0);
    }

    /**
     * Split with a starting chunk index so multi-part files (Excel sheets split
     * independently) keep a unique chunk_index per file — RRF fusion keys on
     * file_name|chunk_index|hash and would otherwise merge same-index chunks.
     */
    public List<TextSegment> split(Document document, int startIndex) {
        String text = document.text();
        List<TextSegment> segments = new ArrayList<>();

        if (text == null || text.isBlank()) return segments;

        // Strip TOC + running header/footer before chunking so noise never enters embeddings.
        // DocNoiseStripper is centralized (also called in DocumentProcessor); keep local
        // stripTocSection as fallback for non-Spring use (unit tests).
        if (docNoiseStripper != null) {
            try {
                text = docNoiseStripper.strip(text).getText();
            } catch (Exception e) {
                text = stripTocSection(text);
            }
        } else {
            text = stripTocSection(text);
        }

        // Split text into blocks: table blocks and non-table blocks
        List<Block> blocks = parseBlocks(text);
        blocks = maybeMergeAdjacentTables(blocks);

        int segmentIndex = startIndex;
        int charOffset = 0;
        String lastSection = "";
        for (Block block : blocks) {
            int before = segments.size();
            if (block.isTable) {
                segments.addAll(processTableBlock(block, document.metadata(), segmentIndex, charOffset, lastSection));
            } else {
                List<TextSegment> produced = processTextBlock(block.content(), document.metadata(), segmentIndex, charOffset);
                // track last section header seen for table context that follows
                for (TextSegment s : produced) {
                    String h = s.metadata().get("section_header");
                    if (h != null && !h.isBlank()) lastSection = h;
                }
                segments.addAll(produced);
            }
            segmentIndex += segments.size() - before;
            charOffset += block.content().length() + 1;
        }

        log.info("Split into {} segments (avg {} chars)", segments.size(),
                segments.isEmpty() ? 0 :
                segments.stream().mapToInt(s -> s.text().length()).sum() / segments.size());

        return segments;
    }

    /**
     * Remove Table-of-Contents section (dotted leaders + page numbers).
     * Handles: explicit "Table of Contents" heading + following TOC lines,
     * plus any dotted/dashed/gapped page-number line in the first 120 lines.
     */
    String stripTocSection(String text) {
        String[] lines = text.split("\\n", -1);
        List<String> kept = new ArrayList<>(lines.length);
        boolean inToc = false;
        int nonTocStreak = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.strip();

            if (TOC_HEADING.matcher(trimmed).matches()) {
                inToc = true;
                nonTocStreak = 0;
                continue; // drop the heading itself
            }

            if (inToc) {
                if (trimmed.isEmpty() || isTocLine(trimmed) || isTocTitleLine(trimmed)) {
                    if (trimmed.isEmpty()) {
                        // blank alone doesn't end TOC; wait for real content
                        kept.add(line);
                        continue;
                    }
                    continue; // drop TOC line
                }
                // markdown header that is not contents ends TOC
                if (trimmed.startsWith("#")) {
                    inToc = false;
                    kept.add(line);
                    continue;
                }
                nonTocStreak++;
                if (nonTocStreak >= 5) {
                    inToc = false;
                } else {
                    // still ambiguous — keep the line to avoid data loss
                    kept.add(line);
                    continue;
                }
                kept.add(line);
                continue;
            }

            // Outside explicit TOC: drop dotted-leader page lines in doc head (first 120 lines)
            if (i < 120 && isTocLine(trimmed)) {
                continue;
            }
            kept.add(line);
        }
        return String.join("\n", kept);
    }

    private boolean isTocLine(String trimmed) {
        if (trimmed.isEmpty() || trimmed.length() > 220) return false;
        if (!trimmed.matches(".*\\d+\\s*$")) return false;
        if (TOC_DOTTED.matcher(trimmed).matches()) return true;
        if (TOC_DASHED.matcher(trimmed).matches()) return true;
        // gapped page numbers only count when line looks like a title + number (letters present, short)
        if (trimmed.length() < 180 && trimmed.matches(".*[A-Za-z].*")
                && TOC_GAPPED.matcher(trimmed).matches()) {
            // avoid killing "Total 5000" style lines: require either dots/dashes already handled
            // or a section-number-like prefix or a long gap — be conservative: require length>20
            return trimmed.length() > 20;
        }
        return false;
    }

    private boolean isTocTitleLine(String trimmed) {
        // TOC entries without page numbers yet (chapter lists under Contents heading)
        if (trimmed.length() > 120) return false;
        return trimmed.matches("(?i)^(chapter|section|part|annex|appendix)\\s+\\d+.*")
                || trimmed.matches("^\\d+(\\.\\d+)*\\s+[A-Z].*");
    }

    /**
     * Parse document into alternating table/non-table blocks.
     * Handles OCR-garbled tables where data rows may be split across lines
     * without leading/trailing pipe characters.
     */
    private List<Block> parseBlocks(String text) {
        List<Block> blocks = new ArrayList<>();
        String[] lines = text.split("\\n", -1);

        StringBuilder currentText = new StringBuilder();
        List<String> currentTable = new ArrayList<>();
        boolean inTable = false;

        for (String line : lines) {
            boolean isTableRow = TABLE_ROW.matcher(line).matches();

            if (isTableRow) {
                // Flush any accumulated text before this table
                if (currentText.length() != 0) {
                    blocks.add(new Block(false, currentText.toString()));
                    currentText.setLength(0);
                }
                inTable = true;
                currentTable.add(line);
            } else if (inTable && isLikelyTableContinuation(line)) {
                // OCR-broken row — still part of the table, append as-is
                currentTable.add(line);
            } else {
                if (inTable) {
                    // End of table — flush table block
                    blocks.add(new Block(true, String.join("\n", currentTable)));
                    currentTable.clear();
                    inTable = false;
                }
                currentText.append(line).append("\n");
            }
        }

        // Flush remaining
        if (inTable && !currentTable.isEmpty()) {
            blocks.add(new Block(true, String.join("\n", currentTable)));
        }
        if (currentText.length() != 0) {
            blocks.add(new Block(false, currentText.toString()));
        }

        return blocks;
    }

    /**
     * Detect lines that are likely continuations of an OCR-broken table row.
     * These lines lack leading | but contain table-like content:
     * numbers, currency (BDT/DDT), single pipe fragments, or short fragments
     * that belong to the preceding table row.
     */
    private boolean isLikelyTableContinuation(String line) {
        String trimmed = line.strip();
        if (trimmed.isEmpty()) return false;

        // Section headers (like "26.2.1 Title:") are NOT table continuations
        if (trimmed.matches("^\\d+\\.\\d+.*")) return false;

        // Lines starting with "## " or markdown headers are NOT table continuations
        if (trimmed.startsWith("#")) return false;

        // Very short lines (brand names, single-word continuations)
        if (trimmed.length() <= 30) return true;

        // Contains currency or price indicators
        if (trimmed.matches(".*\\b(BDT|DDT|Taka|USD|\\d{1,3},\\d{3})\\b.*")) return true;

        // Starts with a number (row index continuation like "2 Director...")
        if (trimmed.matches("^\\d+\\s+.*")) return true;

        // Contains a pipe fragment (partial table cell)
        if (trimmed.contains("|")) return true;

        // Starts with uppercase letter followed by space + lowercase (category name continuation)
        if (trimmed.matches("^[A-Z][a-z]+\\s+.*") && trimmed.length() < 60) return true;

        return false;
    }

    /**
     * Effective table budget: configured docs.table-max-chars clamped to the
     * BGE 512-token ceiling so no chunk is silently truncated by the tokenizer.
     */
    private int tableBudgetOf() {
        int configured = tableMaxChars > 0 ? tableMaxChars : chunkSizeChars;
        return Math.min(configured, MAX_TABLE_BUDGET_CHARS);
    }

    /**
     * Hard-split a single oversize row at word boundaries so even one giant OCR
     * row cannot exceed the table budget (previously the budget guard's second
     * clause let it through whole, and the tokenizer silently dropped its tail).
     */
    private List<String> splitLongRow(String row, int budget) {
        List<String> out = new ArrayList<>();
        if (row == null || row.length() <= budget) {
            if (row != null) out.add(row);
            return out;
        }
        int start = 0;
        while (start < row.length()) {
            int end = Math.min(start + budget, row.length());
            if (end < row.length()) {
                int space = row.lastIndexOf(' ', end);
                if (space > start + budget / 2) end = space;
            }
            out.add(row.substring(start, end));
            start = end;
            while (start < row.length() && row.charAt(start) == ' ') start++;
        }
        return out;
    }

    /**
     * Process a table block — keep header with each row group, 1-row overlap.
     * Non-pipe continuation lines are joined to the previous row (no tail drop).
     */
    private List<TextSegment> processTableBlock(Block block, Metadata baseMeta, int startIndex, int baseOffset, String section) {
        List<TextSegment> segments = new ArrayList<>();
        // Strip boilerplate from table content
        String content = stripBoilerplate(block.content());
        // A numbered heading may sit directly above the table inside the same block —
        // adopt it so the table chunk carries its section label.
        String tableSection = section;
        for (String line : content.split("\\n")) {
            String h = numberedHeaderOf(line.strip());
            if (h != null) tableSection = h;
        }
        String[] lines = content.split("\\n");

        // Extract header (first row) and separator (second row with ---)
        String headerRow = "";
        String sepRow = "";
        int dataStart = 0;
        for (int i = 0; i < lines.length; i++) {
            if (TABLE_ROW.matcher(lines[i]).matches()) {
                if (headerRow.isEmpty()) {
                    headerRow = lines[i];
                    dataStart = i + 1;
                    continue;
                }
                if (TABLE_SEP.matcher(lines[i]).matches()) {
                    sepRow = lines[i];
                    dataStart = i + 1;
                    break;
                }
            }
        }

        // Collect data rows — join non-pipe continuations to previous row instead of dropping
        List<String> dataRows = new ArrayList<>();
        for (int i = dataStart; i < lines.length; i++) {
            if (TABLE_ROW.matcher(lines[i]).matches()) {
                dataRows.add(lines[i]);
            } else if (!lines[i].strip().isEmpty() && !dataRows.isEmpty()) {
                int last = dataRows.size() - 1;
                dataRows.set(last, dataRows.get(last) + " " + lines[i].strip());
            }
        }

        // A single row longer than the budget would previously pass through whole
        // (and be silently truncated by BGE). Pre-split such rows at word
        // boundaries; each piece keeps the header via the normal row-group path.
        int rowBudget = Math.max(200, tableBudgetOf() - headerRow.length() - sepRow.length() - 4);
        List<String> safeRows = new ArrayList<>(dataRows.size());
        for (String r : dataRows) {
            safeRows.addAll(splitLongRow(r, rowBudget));
        }
        dataRows = safeRows;

        if (dataRows.isEmpty()) {
            // Table with no data rows — just store as-is
            String tableText = block.content().trim();
            if (!tableText.isEmpty()) {
                String injected = withSectionPrefix(tableText, tableSection);
                Metadata meta = baseMeta.copy();
                meta.put("content_type", "table");
                meta.put("chunk_index", String.valueOf(startIndex));
                meta.put("chunk_start", String.valueOf(baseOffset));
                meta.put("chunk_end", String.valueOf(baseOffset + injected.length()));
                if (tableSection != null && !tableSection.isBlank()) meta.put("section_header", tableSection);
                segments.add(TextSegment.from(injected, meta));
            }
            return segments;
        }

        // Tables are atomic: keep related rows in ONE chunk up to the table budget
        // so list/compare questions see all rows together.
        // Only genuinely large tables split, by row-group with header repeated.
        int tableBudget = tableBudgetOf();
        StringBuilder chunk = new StringBuilder();
        chunk.append(headerRow).append("\n");
        if (!sepRow.isEmpty()) chunk.append(sepRow).append("\n");

        int chunkIndex = startIndex;
        int localOffset = baseOffset;
        String overlapRow = null;
        String parentTableId = java.util.UUID.randomUUID().toString();
        boolean isFirstChild = true;
        for (String row : dataRows) {
            // Would adding this row exceed the atomic-table budget?
            if (chunk.length() + row.length() > tableBudget && chunk.length() > headerRow.length() + 50) {
                // Flush current chunk
                String chunkText = withSectionPrefix(chunk.toString().trim(), tableSection);
                Metadata meta = baseMeta.copy();
                meta.put("content_type", "table");
                meta.put("parent_table_id", parentTableId);
                if (!isFirstChild || dataRows.size() > 1) meta.put("row_group", isFirstChild ? "0" : "1+");
                isFirstChild = false;
                meta.put("chunk_index", String.valueOf(chunkIndex++));
                meta.put("chunk_start", String.valueOf(localOffset));
                meta.put("chunk_end", String.valueOf(localOffset + chunkText.length()));
                if (tableSection != null && !tableSection.isBlank()) meta.put("section_header", tableSection);
                segments.add(TextSegment.from(chunkText, meta));
                localOffset += chunkText.length() + 1;

                // Start new chunk with header repeated + 1-row overlap for continuity
                chunk = new StringBuilder();
                chunk.append(headerRow).append("\n");
                if (!sepRow.isEmpty()) chunk.append(sepRow).append("\n");
                if (overlapRow != null) chunk.append(overlapRow).append("\n");
            }
            chunk.append(row).append("\n");
            overlapRow = row;
        }

        // Flush remaining rows
        String remaining = withSectionPrefix(chunk.toString().trim(), tableSection);
        if (!remaining.isEmpty()) {
            Metadata meta = baseMeta.copy();
            meta.put("content_type", "table");
            meta.put("parent_table_id", parentTableId);
            meta.put("chunk_index", String.valueOf(chunkIndex));
            meta.put("chunk_start", String.valueOf(localOffset));
            meta.put("chunk_end", String.valueOf(localOffset + remaining.length()));
            if (tableSection != null && !tableSection.isBlank()) meta.put("section_header", tableSection);
            segments.add(TextSegment.from(remaining, meta));
        }

        return segments;
    }

    /**
     * Process a non-table text block — sentence-aware splitting with overlap.
     * Tracks markdown AND numbered policy headings ("4.2 Annual Leave") as section
     * headers; every emitted chunk carries its header as a "[...]" prefix line so the
     * embedding is self-describing (entitlement sentences stay glued to their type).
     */
    private List<TextSegment> processTextBlock(String text, Metadata baseMeta, int startIndex, int baseOffset) {
        List<TextSegment> segments = new ArrayList<>();
        if (text == null || text.isBlank()) return segments;

        // Strip repeated boilerplate (copyright notices from PDF page headers/footers)
        text = stripBoilerplate(text);

        // Split on markdown headers, numbered policy headings, and paragraph breaks
        // (oversize fragments split by sentence)
        List<String> fragments = splitOnBoundaries(text);

        StringBuilder currentChunk = new StringBuilder();
        String currentSection = extractFirstHeader(text);
        String currentSub = "";
        int chunkIndex = startIndex;
        int localOffset = baseOffset;

        for (String fragment : fragments) {
            String firstLine = firstNonBlankLine(fragment);
            String sec = numberedHeaderOf(firstLine);
            String md = sec == null ? mdHeaderOf(firstLine) : null;
            String sub = (sec == null && md == null) ? numberedSubOf(firstLine) : null;
            boolean sectionChange = (sec != null && !sec.equals(currentSection))
                    || (md != null && !md.equals(currentSection))
                    || (sub != null && !sub.equals(currentSub));
            if (sectionChange && currentChunk.length() > 0) {
                // The buffered text belongs to the PREVIOUS section — stamp it BEFORE
                // retracking, or every chunk wears the NEXT section's label (proven in
                // prod: 4.5-probation bodies labeled "[4.6 Maternity Leave / 4.6.4]").
                int next = appendChunk(segments, currentChunk.toString(),
                        effectiveOf(currentSection, currentSub), baseMeta, chunkIndex, localOffset);
                if (next >= 0) {
                    localOffset = next;
                    chunkIndex++;
                }
                currentChunk = new StringBuilder();
            }
            if (sec != null) {
                currentSection = sec;
                currentSub = "";
            } else if (md != null) {
                currentSection = md;
                currentSub = "";
            } else if (sub != null) {
                currentSub = sub;
            }
            String effectiveSection = effectiveOf(currentSection, currentSub);

            // If adding this fragment would exceed chunk size, flush current chunk
            if (currentChunk.length() + fragment.length() > chunkSizeChars && currentChunk.length() > 0) {
                String raw = currentChunk.toString().trim();
                int next = appendChunk(segments, raw, effectiveSection, baseMeta, chunkIndex, localOffset);
                if (next >= 0) {
                    localOffset = next;
                    chunkIndex++;
                }

                // Start new chunk with sentence-aware overlap from the RAW buffer tail
                // (never from the prefixed text — the old "[section]" line would leak
                // into the next chunk as a stale, wrong label).
                currentChunk = new StringBuilder();
                if (raw.length() > overlapChars) {
                    currentChunk.append(getOverlapTail(raw, overlapChars));
                    currentChunk.append("\n");
                } else if (!raw.isEmpty()) {
                    currentChunk.append(raw).append("\n");
                }
            }
            currentChunk.append(fragment);
        }

        // Flush remaining
        String effectiveSection = effectiveOf(currentSection, currentSub);
        appendChunk(segments, currentChunk.toString(), effectiveSection,
                baseMeta, chunkIndex, localOffset);

        return segments;
    }

    private String effectiveOf(String section, String sub) {
        return sub.isEmpty() ? section
                : (section.isBlank() ? sub : section + " / " + sub);
    }

    /** Stamp + store one chunk; returns the next localOffset, or -1 when empty. */
    private int appendChunk(List<TextSegment> segments, String rawText, String section,
            Metadata baseMeta, int chunkIndex, int localOffset) {
        String chunkText = withSectionPrefix(rawText.trim(), section);
        if (chunkText.isEmpty()) return -1;
        Metadata meta = baseMeta.copy();
        meta.put("content_type", "text");
        meta.put("chunk_index", String.valueOf(chunkIndex));
        meta.put("chunk_start", String.valueOf(localOffset));
        meta.put("chunk_end", String.valueOf(localOffset + chunkText.length()));
        if (!section.isBlank()) meta.put("section_header", section);
        segments.add(TextSegment.from(chunkText, meta));
        return localOffset + chunkText.length() + 1;
    }

    /**
     * Split text on structural boundaries (headers, paragraphs), keeping each boundary marker with its content.
     * Oversize fragments (&gt; chunkSize) are further split on sentence boundaries (BGE 512-token safe).
     */
    private List<String> splitOnBoundaries(String text) {
        List<String> parts = new ArrayList<>();
        // Split on markdown headers (## Title), numbered policy headings (4.2 Annual Leave,
        // 4.2.1. ...) or double newlines, keeping the delimiter
        Pattern p = Pattern.compile("(?=(\\n#{1,4}\\s+))|(?=(\\n\\d{1,2}\\.\\d{1,2}(?:\\.\\d{1,3})?\\.?\\s+[A-Za-z]))|(?=(\\n\\n))");
        Matcher m = p.matcher(text);
        int lastEnd = 0;
        while (m.find()) {
            parts.add(text.substring(lastEnd, m.start()));
            lastEnd = m.start();
        }
        parts.add(text.substring(lastEnd));

        // Recursive sentence split for oversize fragments
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            if (part.length() <= chunkSizeChars) {
                result.add(part);
            } else {
                result.addAll(splitOversizeBySentence(part));
            }
        }
        return result;
    }

    private List<String> splitOversizeBySentence(String text) {
        List<String> out = new ArrayList<>();
        // Split keeping sentence terminators
        String[] sentences = text.split("(?<=[.!?])\\s+|\\n{2,}");
        StringBuilder cur = new StringBuilder();
        for (String s : sentences) {
            if (cur.length() + s.length() + 1 > chunkSizeChars && cur.length() > 0) {
                out.add(cur.toString());
                cur = new StringBuilder();
            }
            if (cur.length() > 0) cur.append(" ");
            cur.append(s);
        }
        if (!cur.toString().isBlank()) out.add(cur.toString());
        // Absolute fallback: hard cut (avoids infinite oversize)
        List<String> hard = new ArrayList<>();
        for (String o : out) {
            if (o.length() <= chunkSizeChars * 1.2) {
                hard.add(o);
            } else {
                for (int i = 0; i < o.length(); i += chunkSizeChars) {
                    hard.add(o.substring(i, Math.min(i + chunkSizeChars, o.length())));
                }
            }
        }
        return hard;
    }

    /** Sentence-aware overlap: last sentence/line boundary within the overlap window. */
    private String getOverlapTail(String chunkText, int overlap) {
        int windowStart = Math.max(0, chunkText.length() - overlap * 2);
        String window = chunkText.substring(windowStart);
        int best = -1;
        for (String delim : new String[]{". ", "?\n", "!\n", "\n\n", "\n", ". "}) {
            int idx = window.lastIndexOf(delim);
            if (idx > window.length() - overlap - 50 && idx > best) best = idx + delim.length();
        }
        if (best > 0 && best < window.length()) {
            String tail = window.substring(best).trim();
            if (tail.length() <= overlap * 1.5 && tail.length() > 20) return tail;
        }
        // fallback: hard cut at word boundary
        String hard = chunkText.substring(Math.max(0, chunkText.length() - overlap));
        int sp = hard.indexOf(' ');
        return sp > 0 && sp < 100 ? hard.substring(sp + 1) : hard;
    }

    private String extractFirstHeader(String text) {
        Matcher mh = MD_HEADER.matcher(text);
        if (mh.find()) {
            String h = mh.group(1).replaceAll("^#{1,4}\\s+", "").trim();
            return h.length() > 120 ? h.substring(0, 120) : h;
        }
        for (String line : text.split("\\n", -1)) {
            String h = numberedHeaderOf(line);
            if (h != null) return h;
        }
        return "";
    }

    /** First non-blank line of a fragment (header detection point). */
    private String firstNonBlankLine(String fragment) {
        for (String line : fragment.split("\\n", -1)) {
            if (!line.isBlank()) return line.strip();
        }
        return "";
    }

    /** Numbered section title ("4.2 Annual Leave") or null. Title must start with a letter. */
    private String numberedHeaderOf(String line) {
        if (line == null) return null;
        Matcher m = NUMBERED_SECTION.matcher(line.strip());
        if (m.matches()) {
            String h = (m.group(1) + " " + m.group(2)).trim();
            return h.length() > 120 ? h.substring(0, 120) : h;
        }
        return null;
    }

    /** Numbered sub-point id ("4.2.1") or null. */
    private String numberedSubOf(String line) {
        if (line == null) return null;
        Matcher m = NUMBERED_SUB.matcher(line.strip());
        if (m.matches()) return m.group(1);
        return null;
    }

    /** Markdown '#'-style header text or null. */
    private String mdHeaderOf(String line) {
        if (line == null) return null;
        Matcher m = HEADER_LINE.matcher(line.strip());
        if (m.matches()) {
            String h = m.group(1).trim();
            return h.length() > 120 ? h.substring(0, 120) : h;
        }
        return null;
    }

    /**
     * Prefix a chunk with its section label so the embedding is self-describing.
     * Entitlement sentences ("20 days ...") stay glued to their type ("Annual Leave").
     */
    private String withSectionPrefix(String chunkText, String header) {
        if (chunkText == null || chunkText.isEmpty() || header == null || header.isBlank()) return chunkText;
        String tag = "[" + header.strip() + "]";
        String firstLine = chunkText.contains("\n")
                ? chunkText.substring(0, chunkText.indexOf('\n')).strip()
                : chunkText.strip();
        if (firstLine.equals(tag)) return chunkText;
        return tag + "\n" + chunkText;
    }

    /**
     * Strip repeated copyright/boilerplate lines that appear on every PDF page.
     * These waste embedding space and confuse retrieval.
     */
    private String stripBoilerplate(String text) {
        StringBuilder cleaned = new StringBuilder();
        for (String line : text.split("\\n", -1)) {
            if (BOILERPLATE.matcher(line).find()) continue;
            cleaned.append(line).append("\n");
        }
        return cleaned.toString();
    }

    /**
     * Merge back-to-back tables under the same section when combined they still
     * fit the atomic-table budget. HR docs often split one logical table into
     * two markdown tables (Amount + Conditions) — keep them in one chunk.
     */
    private List<Block> maybeMergeAdjacentTables(List<Block> blocks) {
        if (!mergeAdjacentTables || blocks.size() < 2) return blocks;
        int budget = tableBudgetOf();
        List<Block> out = new ArrayList<>();
        for (Block b : blocks) {
            if (!out.isEmpty() && b.isTable() && out.get(out.size() - 1).isTable()) {
                Block prev = out.get(out.size() - 1);
                if (prev.content().length() + b.content().length() + 2 <= budget) {
                    out.set(out.size() - 1,
                            new Block(true, prev.content() + "\n" + b.content()));
                    continue;
                }
            }
            // Table + tiny text gap + table: gap is likely a caption — fold it in.
            if (out.size() >= 2 && b.isTable()
                    && !out.get(out.size() - 1).isTable()
                    && out.get(out.size() - 2).isTable()) {
                String gap = out.get(out.size() - 1).content();
                Block prevTable = out.get(out.size() - 2);
                if (gap.strip().length() < 300 && !gap.contains("#")
                        && prevTable.content().length() + gap.length() + b.content().length() + 2 <= budget) {
                    out.remove(out.size() - 1);
                    out.remove(out.size() - 1);
                    out.add(new Block(true, prevTable.content() + "\n" + gap + "\n" + b.content()));
                    continue;
                }
            }
            out.add(b);
        }
        return out;
    }

    private static final class Block {
        private final boolean isTable;
        private final String content;

        Block(boolean isTable, String content) {
            this.isTable = isTable;
            this.content = content;
        }

        boolean isTable() {
            return isTable;
        }

        String content() {
            return content;
        }
    }
}
