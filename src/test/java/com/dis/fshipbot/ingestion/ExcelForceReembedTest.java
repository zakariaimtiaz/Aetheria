package com.dis.fshipbot.ingestion;

import com.dis.fshipbot.repository.DocumentRepository;
import com.dis.fshipbot.util.ContentHashUtil;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * "Force full re-embed" must reach the Excel pipeline.
 *
 * <p>Excel workbooks are chunked by the same {@link DocumentSplitter} as markdown,
 * so a chunker change leaves the file bytes — and therefore the content hash —
 * untouched. Without threading {@code force} into {@link
 * ExcelContentProcessor}, every workbook would be silently skipped after a
 * chunker change while the admin UI reported a successful forced re-embed.
 *
 * <p>Both tests below set up an <i>unchanged</i> workbook (DB hash == file hash),
 * which is the exact case the hash check short-circuits.
 */
public class ExcelForceReembedTest {

    private static final String FILE_HASH = "deadbeef";

    /** No-op transaction manager: the swap just runs the callback inline. */
    private static class InlineTxManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }

    private static class Harness {
        final EmbeddingModel embeddingModel = Mockito.mock(EmbeddingModel.class);
        final DocumentRepository repository = Mockito.mock(DocumentRepository.class);
        // update(...) is called with 14 varargs, so match on the method name via a
        // default answer instead of a varargs matcher (which does not line up with
        // the expanded argument array).
        final JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class, invocation ->
                "update".equals(invocation.getMethod().getName())
                        ? 1
                        : Mockito.RETURNS_DEFAULTS.answer(invocation));

        Harness() {
            // Embedding model returns one vector per requested segment.
            Mockito.when(embeddingModel.embedAll(ArgumentMatchers.anyList()))
                    .thenAnswer(inv -> {
                        List<TextSegment> in = inv.getArgument(0);
                        List<Embedding> out = new ArrayList<>(in.size());
                        for (int i = 0; i < in.size(); i++) {
                            out.add(Embedding.from(new float[384]));
                        }
                        return Response.from(out);
                    });

            // Workbook already indexed, with an IDENTICAL hash -> the skip path.
            DocumentRepository.FileInfo existing = new DocumentRepository.FileInfo();
            existing.setFileName("policy.xlsx");
            existing.setFileHash(FILE_HASH);
            existing.setFileSize(1L);
            Mockito.when(repository.getLatestFileInfo("policy.xlsx")).thenReturn(existing);
            Mockito.when(repository.getNextVersion("policy.xlsx")).thenReturn(2);
        }

        ExcelContentProcessor processor(DocumentSplitter splitter) {
            ExcelContentProcessor p = new ExcelContentProcessor();
            ReflectionTestUtils.setField(p, "embeddingModel", embeddingModel);
            ReflectionTestUtils.setField(p, "documentSplitter", splitter);
            ReflectionTestUtils.setField(p, "documentRepository", repository);
            ReflectionTestUtils.setField(p, "contentHashUtil", hashUtil());
            ReflectionTestUtils.setField(p, "jdbcTemplate", jdbcTemplate);
            ReflectionTestUtils.setField(p, "transactionManager", new InlineTxManager());
            return p;
        }

        private ContentHashUtil hashUtil() {
            ContentHashUtil util = Mockito.spy(new ContentHashUtil());
            Mockito.doReturn(FILE_HASH)
                    .when(util).calculateFileHash(ArgumentMatchers.any(File.class));
            return util;
        }
    }

    /** Splitter stand-in returning well-formed segments with the metadata ExcelContentProcessor reads. */
    private static DocumentSplitter splitterReturning(int segments) {
        DocumentSplitter splitter = Mockito.mock(DocumentSplitter.class);
        Mockito.when(splitter.split(ArgumentMatchers.any(),
                        ArgumentMatchers.anyInt()))
                .thenAnswer(inv -> {
                    int offset = inv.getArgument(1);
                    List<TextSegment> out = new ArrayList<>();
                    for (int i = 0; i < segments; i++) {
                        TextSegment s = TextSegment.from("| Annual | " + (i + offset) + " |");
                        s.metadata().put("chunk_index", String.valueOf(i + offset));
                        s.metadata().put("chunk_start", String.valueOf(i * 10));
                        s.metadata().put("chunk_end", String.valueOf(i * 10 + 9));
                        out.add(s);
                    }
                    return out;
                });
        return splitter;
    }

    private File writeWorkbook(Path dir) throws Exception {
        File file = dir.resolve("policy.xlsx").toFile();
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Leave");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Type");
            header.createCell(1).setCellValue("Days");
            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("Annual");
            r1.createCell(1).setCellValue(20);
            try (FileOutputStream out = new FileOutputStream(file)) {
                workbook.write(out);
            }
        }
        return file;
    }

    /** Same content hash as the stored row, but force=true: must re-embed anyway. */
    @Test
    public void forceTrueReembedsUnchangedWorkbook(@TempDir Path dir) throws Exception {
        File file = writeWorkbook(dir);
        Harness h = new Harness();
        ExcelContentProcessor.ProcessingResult result = new ExcelContentProcessor.ProcessingResult();

        Boolean outcome = h.processor(splitterReturning(2)).processSingleFile(file, result, true);

        Assertions.assertEquals(Boolean.TRUE, outcome,
                "force=true must re-index an existing workbook, not report it as new");
        Assertions.assertTrue(result.getTotalSegmentsStored() > 0,
                "force=true must store chunks for an unchanged workbook");
        Mockito.verify(h.repository).removeByFileName("policy.xlsx");
        Mockito.verify(h.embeddingModel, Mockito.atLeastOnce()).embedAll(ArgumentMatchers.anyList());
    }

    /** force=false: the identical hash must still short-circuit to "skipped". */
    @Test
    public void forceFalseSkipsUnchangedWorkbook(@TempDir Path dir) throws Exception {
        File file = writeWorkbook(dir);
        Harness h = new Harness();
        ExcelContentProcessor.ProcessingResult result = new ExcelContentProcessor.ProcessingResult();

        Boolean outcome = h.processor(splitterReturning(2)).processSingleFile(file, result, false);

        Assertions.assertNull(outcome, "unchanged workbook must be skipped when force=false");
        Assertions.assertEquals(0, result.getTotalSegmentsStored());
        Mockito.verify(h.repository, Mockito.never()).removeByFileName(ArgumentMatchers.anyString());
        Mockito.verify(h.embeddingModel, Mockito.never()).embedAll(ArgumentMatchers.anyList());
    }
}
