package com.dis.fshipbot;

import com.dis.fshipbot.ingestion.DocNoiseStripper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Converted markdown must land without TOC / running headers-footers. Kept. */
public class StripConversionTest {

    private static final String MARKITDOWN_LIKE =
            "# Table of Contents\n"
            + "4.2 Annual Leave .... 12\n"
            + "4.3 Casual Leave 15\n"
            + "4.6 Maternity Leave .... 18\n"
            + "\n"
            + "Friendship Human Resources Policies\n"
            + "& Process/Procedures Manual\n"
            + "4.2.1. Employees will be entitled to a maximum of 20 days of annual leave.\n"
            + "Date/Month Issued: May 2023 Page 29 of 163\n"
            + "Version: 18.0\n"
            + "Friendship Human Resources Policies\n"
            + "& Process/Procedures Manual\n"
            + "4.4.3. Employees are entitled to avail 14 (fourteen) days sick leave in one calendar year.\n"
            + "Friendship Human Resources Policies\n"
            + "& Process/Procedures Manual\n";

    @Test
    public void conversionOutputIsClean() {
        DocNoiseStripper.StripResult r = new DocNoiseStripper().strip(MARKITDOWN_LIKE);
        String cleaned = r.getText();
        Assertions.assertTrue(cleaned.contains("maximum of 20 days"),
                "body must survive:\n" + cleaned);
        Assertions.assertTrue(cleaned.contains("fourteen) days sick"),
                "body must survive:\n" + cleaned);
        Assertions.assertFalse(cleaned.contains("Table of Contents"), "TOC heading must go");
        Assertions.assertFalse(cleaned.contains("Process/Procedures Manual"),
                "running header must go");
        Assertions.assertFalse(cleaned.contains("Date/Month Issued"),
                "issued footer must go");
        Assertions.assertTrue(r.getTocRemoved() > 0, "expected TOC removals");
        Assertions.assertTrue(r.getHeaderFooterRemoved() > 0, "expected header/footer removals");
    }
}
