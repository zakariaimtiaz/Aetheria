package com.dis.fshipbot.ingestion;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Optional;

/** Sheet-to-markdown conversion: structure, escaping, empties. No Spring, no DB. */
public class ExcelSheetTest {

    private final ExcelContentProcessor processor = new ExcelContentProcessor();

    private Sheet sheetWithData() {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Leave");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("Type");
        header.createCell(1).setCellValue("Days");
        header.createCell(2).setCellValue("Note");
        Row r1 = sheet.createRow(1);
        r1.createCell(0).setCellValue("Annual");
        r1.createCell(1).setCellValue(20);
        r1.createCell(2).setCellValue("max | carry 10");
        sheet.createRow(2); // fully empty row — must be skipped
        Row r3 = sheet.createRow(3);
        r3.createCell(0).setCellValue("Casual");
        r3.createCell(1).setCellValue(10);
        return sheet;
    }

    @Test
    public void sheetRendersMarkdownTable() {
        Optional<String> md = processor.sheetToMarkdown(sheetWithData(), "policy.xlsx");
        Assertions.assertTrue(md.isPresent(), "expected markdown for data sheet");
        String text = md.get();
        Assertions.assertTrue(text.contains("## policy.xlsx — Leave"), "section header line missing");
        Assertions.assertTrue(text.contains("2 rows x 3 columns"), "summary line wrong: " + text);
        Assertions.assertTrue(text.contains("| Type | Days | Note |"), "header row missing");
        Assertions.assertTrue(text.contains("| --- | --- | --- |"), "separator row missing");
        Assertions.assertTrue(text.contains("| Annual | 20 |"), "data row missing");
        Assertions.assertTrue(text.contains("max \\| carry 10"), "pipe must be escaped");
        Assertions.assertFalse(text.contains("|  |  |"), "empty row must be skipped");
    }

    @Test
    public void emptySheetYieldsEmpty() {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Blank");
        Assertions.assertTrue(processor.sheetToMarkdown(sheet, "empty.xlsx").isEmpty());
    }

    @Test
    public void headersOnlyYieldsEmpty() {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("HeadersOnly");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("Type");
        Assertions.assertTrue(processor.sheetToMarkdown(sheet, "headers.xlsx").isEmpty());
    }
}
