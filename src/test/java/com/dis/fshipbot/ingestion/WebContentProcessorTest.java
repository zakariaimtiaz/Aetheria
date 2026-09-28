package com.dis.fshipbot.ingestion;

import com.dis.fshipbot.repository.DocumentRepository;
import com.dis.fshipbot.util.ContentHashUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Web ingestion: URL validation, HTTP handling, cross-page boilerplate removal,
 * thin-page refusal, and — most importantly — that a failure is REPORTED rather
 * than silently destroying the content already stored for a page.
 *
 * <p>Fixtures are served by the JDK's built-in {@link HttpServer}, so the suite
 * needs no new dependency and never touches the real friendship.ngo.
 */
public class WebContentProcessorTest {

    private static final String PAGE_A = "/about-us/";
    private static final String PAGE_B = "/what-we-do/";

    /** Chrome deliberately repeated verbatim on every page. */
    private static final String SHARED_NAV = "Home About Us What We Do News Contact Donate Careers";
    private static final String SHARED_COOKIE =
            "We use cookies This site uses cookies and gives you control over what is shared";

    private HttpServer server;
    private String base;

    private EmbeddingModel embeddingModel;
    private DocumentRepository repository;
    private JdbcTemplate jdbcTemplate;

    private int insertCalls;
    private int deleteCalls;
    private boolean insertsSucceed = true;
    /** Every text handed to INSERT, in order — assertions need all pages, not just the last. */
    private final List<String> allStoredTexts = new ArrayList<>();
    /** fileName -> content hash the pipeline computed, so a second run can be made to match. */
    private final java.util.Map<String, String> hashByFile = new java.util.HashMap<>();

    /** No-op transaction manager: runs the swap callback inline. */
    private static class InlineTxManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object t, TransactionDefinition d) { }
        @Override protected void doCommit(DefaultTransactionStatus s) { }
        @Override protected void doRollback(DefaultTransactionStatus s) { }
    }

    @BeforeEach
    public void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        embeddingModel = Mockito.mock(EmbeddingModel.class);
        Mockito.when(embeddingModel.embedAll(ArgumentMatchers.anyList())).thenAnswer(inv -> {
            List<TextSegment> in = inv.getArgument(0);
            List<Embedding> out = new ArrayList<>(in.size());
            for (int i = 0; i < in.size(); i++) {
                out.add(Embedding.from(new float[384]));
            }
            return Response.from(out);
        });

        repository = Mockito.mock(DocumentRepository.class);
        Mockito.when(repository.getNextVersion(ArgumentMatchers.anyString())).thenReturn(1);
        Mockito.doAnswer(inv -> {
            deleteCalls++;
            return null;
        }).when(repository).removeByFileName(ArgumentMatchers.anyString());

        // update(sql, ...) is called with many varargs, so answer by METHOD NAME
        // rather than a varargs matcher (which does not line up with the expanded
        // argument array). Mockito exposes the varargs as individual arguments,
        // so for INSERT_CHUNK_SQL the indices are:
        //   0=sql 1=embedding_id 2=file_name 3=file_type 4=file_hash ... 8=text
        jdbcTemplate = Mockito.mock(JdbcTemplate.class, invocation -> {
            if (!"update".equals(invocation.getMethod().getName())) {
                return Mockito.RETURNS_DEFAULTS.answer(invocation);
            }
            if (!insertsSucceed) {
                throw new IllegalStateException("simulated insert failure");
            }
            Object[] args = invocation.getArguments();
            hashByFile.put(String.valueOf(args[2]), String.valueOf(args[4]));
            allStoredTexts.add(String.valueOf(args[8]));
            insertCalls++;
            return 1;
        });
        insertCalls = 0;
        deleteCalls = 0;
        insertsSucceed = true;
        allStoredTexts.clear();
        hashByFile.clear();
    }

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private WebContentProcessor processor(String... urls) {
        WebContentProcessor p = new WebContentProcessor();
        ReflectionTestUtils.setField(p, "embeddingModel", embeddingModel);
        ReflectionTestUtils.setField(p, "documentSplitter", new StubSplitter());
        ReflectionTestUtils.setField(p, "documentRepository", repository);
        ReflectionTestUtils.setField(p, "contentHashUtil", new ContentHashUtil());
        ReflectionTestUtils.setField(p, "jdbcTemplate", jdbcTemplate);
        ReflectionTestUtils.setField(p, "transactionManager", new InlineTxManager());
        ReflectionTestUtils.setField(p, "docNoiseStripper", new DocNoiseStripper());
        ReflectionTestUtils.setField(p, "webUrls", new ArrayList<>(Arrays.asList(urls)));
        ReflectionTestUtils.setField(p, "batchSize", 10);
        ReflectionTestUtils.setField(p, "timeoutSeconds", 5);
        ReflectionTestUtils.setField(p, "userAgent", "test-agent");
        ReflectionTestUtils.setField(p, "minContentChars", 300);
        ReflectionTestUtils.setField(p, "minAfterStripChars", 200);
        return p;
    }

    /** One segment per page, carrying the metadata the processor reads. */
    private static class StubSplitter extends DocumentSplitter {
        @Override
        public List<TextSegment> split(Document document) {
            return split(document, 0);
        }

        @Override
        public List<TextSegment> split(Document document, int startIndex) {
            TextSegment s = TextSegment.from(document.text());
            s.metadata().put("chunk_index", String.valueOf(startIndex));
            s.metadata().put("chunk_start", "0");
            s.metadata().put("chunk_end",
                    String.valueOf(Math.max(0, document.text().length() - 1)));
            List<TextSegment> out = new ArrayList<>();
            out.add(s);
            return out;
        }
    }

    // ---- fixtures ------------------------------------------------------------

    private void serve(String path, int status, String contentType, String body) {
        server.createContext(path, (HttpExchange ex) -> {
            try {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", contentType);
                ex.sendResponseHeaders(status, bytes.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(bytes);
                }
            } catch (IOException e) {
                // client hung up; nothing useful to do in a fixture
            }
        });
    }

    private String page(String title, String... uniqueParagraphs) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><title>").append(title).append(" - Friendship NGO</title></head><body>");
        sb.append("<nav>").append(SHARED_NAV).append("</nav>");
        sb.append("<div class='cookie'>").append(SHARED_COOKIE).append("</div>");
        for (String p : uniqueParagraphs) {
            sb.append("<p>").append(p).append("</p>");
        }
        sb.append("<footer>").append(SHARED_NAV).append("</footer>");
        sb.append("</body></html>");
        return sb.toString();
    }

    /** Unique text long enough to clear the 300-char minimum. */
    private String longParagraph(String seed) {
        StringBuilder sb = new StringBuilder(seed);
        while (sb.length() < 400) {
            sb.append(' ').append(seed);
        }
        return sb.toString();
    }

    private void serveTwoGoodPages() {
        serve(PAGE_A, 200, "text/html; charset=UTF-8",
                page("About Us", longParagraph("Friendship was founded in 2002 by Runa Khan")));
        serve(PAGE_B, 200, "text/html; charset=UTF-8",
                page("What We Do", longParagraph("Friendship runs health programmes in eleven districts")));
    }

    // ---- tests ---------------------------------------------------------------

    @Test
    public void ingestsPagesAndStripsSharedBoilerplate() {
        serveTwoGoodPages();

        WebContentProcessor.ProcessingResult result =
                processor(base + PAGE_A, base + PAGE_B).processConfiguredUrls(false);

        Assertions.assertEquals(0, result.getFailedUrls(),
                "no page should fail: " + result.getFailedFiles());
        Assertions.assertEquals(2, result.getNewUrls());
        Assertions.assertEquals(2, insertCalls);

        String allText = String.join("\n", allStoredTexts);
        Assertions.assertFalse(allText.contains(SHARED_NAV), "shared nav must be stripped");
        Assertions.assertFalse(allText.contains(SHARED_COOKIE),
                "shared cookie banner must be stripped");
        Assertions.assertTrue(allText.contains("founded in 2002"),
                "page A unique content must survive");
        Assertions.assertTrue(allText.contains("eleven districts"),
                "page B unique content must survive");
    }

    @Test
    public void urlWithoutDoubleSlashIsRejectedWithSuggestedFix() {
        // "https:host" parses as an OPAQUE java.net.URL with an EMPTY host and
        // does NOT throw, so it must be caught before we try to connect.
        Exception e = Assertions.assertThrows(Exception.class,
                () -> WebContentProcessor.validateUrl("https:friendship.ngo"));
        Assertions.assertTrue(e.getMessage().contains("https://friendship.ngo"),
                "message should suggest the fix, was: " + e.getMessage());
    }

    @Test
    public void nonHttpProtocolIsRejected() {
        Exception e = Assertions.assertThrows(Exception.class,
                () -> WebContentProcessor.validateUrl("file:///etc/passwd"));
        Assertions.assertTrue(e.getMessage().toLowerCase().contains("protocol"), e.getMessage());
    }

    @Test
    public void validUrlsPassValidation() throws Exception {
        Assertions.assertEquals("friendship.ngo",
                WebContentProcessor.validateUrl("https://friendship.ngo").getHost());
        Assertions.assertEquals("friendship.ngo",
                WebContentProcessor.validateUrl("https://friendship.ngo/").getHost());
    }

    @Test
    public void httpErrorPageIsNotIngested() {
        serve(PAGE_A, 404, "text/html; charset=UTF-8", page("Not Found", longParagraph("404 body text")));

        WebContentProcessor.ProcessingResult result =
                processor(base + PAGE_A).processConfiguredUrls(false);

        Assertions.assertEquals(1, result.getFailedUrls());
        Assertions.assertEquals(0, insertCalls, "a 404 must never be embedded");
        Assertions.assertTrue(String.valueOf(result.getFailedFiles().values().iterator().next()).contains("404"),
                "failure reason should name the status: " + result.getFailedFiles());
    }

    @Test
    public void nonHtmlResponseIsRejected() {
        serve(PAGE_A, 200, "application/pdf", "%PDF-1.4 junk");

        WebContentProcessor.ProcessingResult result =
                processor(base + PAGE_A).processConfiguredUrls(false);

        Assertions.assertEquals(1, result.getFailedUrls());
        Assertions.assertEquals(0, insertCalls);
    }

    @Test
    public void boilerplateOnlyPagesAreRefusedRatherThanStoredEmpty() {
        // Identical chrome on both pages => cross-page stripping removes all of
        // it. Neither page has unique content, so neither may be indexed.
        serve(PAGE_A, 200, "text/html; charset=UTF-8", page("About Us"));
        serve(PAGE_B, 200, "text/html; charset=UTF-8", page("What We Do"));

        WebContentProcessor.ProcessingResult result =
                processor(base + PAGE_A, base + PAGE_B).processConfiguredUrls(false);

        Assertions.assertEquals(0, insertCalls,
                "boilerplate-only pages must not be stored; failures=" + result.getFailedFiles());
    }

    @Test
    public void failingSwapIsReportedAsFailed() {
        serve(PAGE_A, 200, "text/html; charset=UTF-8",
                page("About Us", longParagraph("original page content about Friendship")));

        DocumentRepository.FileInfo stored = new DocumentRepository.FileInfo();
        stored.setFileName(base + PAGE_A);
        stored.setFileHash("a-different-hash-entirely");
        Mockito.when(repository.getLatestFileInfo(base + PAGE_A)).thenReturn(stored);

        insertsSucceed = false;
        WebContentProcessor.ProcessingResult result =
                processor(base + PAGE_A).processConfiguredUrls(false);

        // The old code deleted first and swallowed the insert error, reporting
        // success with zero chunks. A failure must surface as a failure.
        Assertions.assertEquals(1, result.getFailedUrls(),
                "a failing swap must be reported as failed, not as a successful ingest");
        Assertions.assertEquals(0, result.getNewUrls());
        Assertions.assertEquals(0, result.getTotalSegmentsStored());
    }

    @Test
    public void unchangedPageIsSkippedOnIncrementalRun() {
        serveTwoGoodPages();

        WebContentProcessor.ProcessingResult first =
                processor(base + PAGE_A, base + PAGE_B).processConfiguredUrls(false);
        Assertions.assertEquals(2, first.getNewUrls());
        String hashOfA = hashByFile.get(base + PAGE_A);
        Assertions.assertNotNull(hashOfA,
                "page A should have been stored; keys=" + hashByFile.keySet() + " base=" + base);

        DocumentRepository.FileInfo same = new DocumentRepository.FileInfo();
        same.setFileName(base + PAGE_A);
        same.setFileHash(hashOfA);
        Mockito.when(repository.getLatestFileInfo(base + PAGE_A)).thenReturn(same);

        int before = insertCalls;
        WebContentProcessor.ProcessingResult second =
                processor(base + PAGE_A, base + PAGE_B).processConfiguredUrls(false);

        Assertions.assertEquals(1, second.getSkippedUrls(),
                "page A (hash matches) should be skipped");
        Assertions.assertEquals(1, second.getNewUrls(),
                "page B (no stored row) should still ingest");
        Assertions.assertEquals(before + 1, insertCalls);
    }

    @Test
    public void forceTrueReembedsUnchangedPage() {
        serveTwoGoodPages();

        processor(base + PAGE_A, base + PAGE_B).processConfiguredUrls(false);
        int afterFirst = insertCalls;

        // Both pages now have a stored row; force must ignore the hashes.
        DocumentRepository.FileInfo same = new DocumentRepository.FileInfo();
        same.setFileHash("whatever");
        Mockito.when(repository.getLatestFileInfo(ArgumentMatchers.anyString())).thenReturn(same);

        WebContentProcessor.ProcessingResult forced =
                processor(base + PAGE_A, base + PAGE_B).processConfiguredUrls(true);

        Assertions.assertEquals(0, forced.getSkippedUrls(), "force must skip nothing");
        Assertions.assertEquals(2, forced.getUpdatedUrls());
        Assertions.assertTrue(insertCalls > afterFirst, "force=true must re-embed");
    }

    @Test
    public void emptyUrlListIsANoOp() {
        WebContentProcessor.ProcessingResult result = processor().processConfiguredUrls(false);
        Assertions.assertEquals(0, result.getTotalDocuments());
        Assertions.assertEquals(0, insertCalls);
    }

    @Test
    public void oneUnreachableUrlDoesNotBlockTheOthers() {
        serve(PAGE_A, 200, "text/html; charset=UTF-8",
                page("About Us", longParagraph("this page is fine and has real content")));
        // PAGE_B is deliberately never served -> connection refused.

        WebContentProcessor.ProcessingResult result =
                processor(base + PAGE_A, base + PAGE_B).processConfiguredUrls(false);

        Assertions.assertEquals(1, result.getNewUrls(), "the reachable page must still ingest");
        Assertions.assertEquals(1, result.getFailedUrls(), "the dead URL must be reported failed");
        Assertions.assertEquals(1, insertCalls);
    }
}
