package com.dis.fshipbot;

import com.dis.fshipbot.ingestion.DocumentSplitter;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

/** Section labels must match chunk bodies (off-by-one regression test). Kept. */
public class SplitterSectionTest {

    private DocumentSplitter splitter() throws Exception {
        DocumentSplitter s = new DocumentSplitter();
        set(s, "chunkSizeChars", 2000);
        set(s, "overlapChars", 100);
        set(s, "tableMaxChars", 2500);
        return s;
    }

    private static void set(Object o, String field, int value) throws Exception {
        Field f = o.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.setInt(o, value);
    }

    @Test
    public void chunkLabelMatchesBody() throws Exception {
        String doc = "4.5 Leave on Probation\n"
                + "PROBATION-BODY Employees on probation take casual leave on pro-rated basis. "
                + "PROBATION-BODY Annual leave accrues but cannot be taken during probation. "
                + "PROBATION-BODY Satisfactory completion moves leave to normal rules.\n\n"
                + "4.6 Maternity Leave\n"
                + "MATERNITY-BODY Female employees get sixteen weeks paid maternity leave. "
                + "MATERNITY-BODY This covers prenatal and postnatal periods. "
                + "MATERNITY-BODY No leave if more than two surviving children at confinement.\n";
        List<TextSegment> chunks = splitter().split(new Document(doc, new Metadata()));
        Assertions.assertTrue(chunks.size() >= 2, "expected section split, got " + chunks.size());
        for (TextSegment c : chunks) {
            String header = String.valueOf(c.metadata().get("section_header"));
            String body = c.text();
            if (body.contains("PROBATION-BODY")) {
                Assertions.assertTrue(header.startsWith("4.5"),
                        "probation body mislabeled as [" + header + "]");
            }
            if (body.contains("MATERNITY-BODY")) {
                Assertions.assertTrue(header.startsWith("4.6"),
                        "maternity body mislabeled as [" + header + "]");
            }
            // No stale injected label carried inside the body
            String[] lines = body.split("\\R");
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].strip();
                if (line.matches("^\\[.+\\]$") && !line.equals("[" + header + "]")) {
                    Assertions.fail("stale label line '" + line + "' inside chunk [" + header + "]");
                }
            }
        }
    }
}
