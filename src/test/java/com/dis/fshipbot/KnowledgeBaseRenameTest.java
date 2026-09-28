package com.dis.fshipbot;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The knowledge-base table was renamed from {@code friendship_knowledge_base}
 * to {@code knowledge_base}.
 *
 * <p>A partial rename is invisible until a query fails at runtime against the
 * real database: the app's DDL is not auto-applied, so a table name that only
 * appears in one of several places produces a working build, a green test run,
 * and a broken production query. These tests read the sources as text and fail
 * on any lingering old name outside the one migration block that must keep it.
 */
public class KnowledgeBaseRenameTest {

    private static final String OLD_NAME = "friendship_knowledge_base";
    private static final String NEW_NAME = "knowledge_base";

    private static final Path SRC = Paths.get("src");

    private static List<Path> sourceFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(SRC)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".java") || n.endsWith(".sql") || n.endsWith(".properties");
                    })
                    // Target build output mirrors src and would double-report.
                    .filter(p -> !p.toString().contains("target"))
                    .forEach(files::add);
        }
        return files;
    }

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    void noSourceFileReferencesTheOldTableName() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : sourceFiles()) {
            String normalized = file.toString().replace('\\', '/');
            // This test names the old table on purpose; schema.sql keeps the
            // guarded rename migration. Everything else must be clean.
            if (normalized.endsWith("db/schema.sql")
                    || normalized.endsWith("KnowledgeBaseRenameTest.java")) {
                continue;
            }
            if (read(file).contains(OLD_NAME)) {
                offenders.add(normalized);
            }
        }
        Assertions.assertTrue(offenders.isEmpty(),
                "these files still reference the old table name: " + offenders);
    }

    @Test
    void schemaSqlKeepsExactlyOneGuardedRenameMigration() throws IOException {
        String schema = read(Paths.get("src", "main", "resources", "db", "schema.sql"));

        Assertions.assertTrue(schema.contains("to_regclass('fship_ai." + NEW_NAME + "') IS NULL")
                        && schema.contains("to_regclass('fship_ai." + OLD_NAME + "') IS NOT NULL")
                        && schema.contains("RENAME TO"),
                "schema.sql must migrate an existing deployment by renaming the table, "
                        + "so its data and indexes survive");
        Assertions.assertTrue(schema.contains("to_regclass('fship_ai." + NEW_NAME + "') IS NULL"),
                "the rename must be guarded, otherwise re-running schema.sql on an already-migrated "
                        + "database fails or re-renames a fresh table");
        Assertions.assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS fship_ai." + NEW_NAME),
                "schema.sql must create the new table for a fresh database");
    }

    @Test
    void schemaSqlDoesNotCreateTheOldTableAnymore() throws IOException {
        String schema = read(Paths.get("src", "main", "resources", "db", "schema.sql"));
        Assertions.assertFalse(schema.contains("CREATE TABLE IF NOT EXISTS fship_ai." + OLD_NAME),
                "a fresh database must not end up with both tables");
    }

    @Test
    void allIndexesAndConstraintsUseTheNewName() throws IOException {
        String schema = read(Paths.get("src", "main", "resources", "db", "schema.sql"));
        for (String statement : schema.split(";")) {
            // The migration block is the one place the old name may appear; its
            // RAISE NOTICE is inside it, so split on the statement boundary.
            if (statement.contains(OLD_NAME) && !statement.contains("RENAME TO")) {
                Assertions.fail("stale reference to the old table name in schema.sql: "
                        + statement.trim().replaceAll("\\s+", " "));
            }
        }
    }

    @Test
    void configuredTableNameMatchesTheSchema() throws IOException {
        String props = read(Paths.get("src", "main", "resources", "database.properties"));
        Assertions.assertTrue(props.contains("vector.store.table-name=" + NEW_NAME),
                "database.properties must point the vector store at the new table");
        Assertions.assertFalse(props.contains("vector.store.table-name=" + OLD_NAME));
    }

    @Test
    void vectorStoreDefaultMatchesTheSchema() throws IOException {
        // VectorStoreConfig and RetrievalDiagnosticsService each carry a @Value
        // default; both must agree with schema.sql or the app talks to a
        // different table depending on whether the property is set.
        for (String file : new String[]{
                "src/main/java/com/dis/fshipbot/config/VectorStoreConfig.java",
                "src/main/java/com/dis/fshipbot/service/RetrievalDiagnosticsService.java"}) {
            String content = read(Paths.get(file));
            Assertions.assertTrue(content.contains("vector.store.table-name:" + NEW_NAME),
                    file + " must default to the new table name");
        }
    }

    @Test
    void repositoriesQueryTheNewTable() throws IOException {
        for (String file : new String[]{
                "src/main/java/com/dis/fshipbot/repository/DocumentRepository.java",
                "src/main/java/com/dis/fshipbot/ingestion/DocumentProcessor.java",
                "src/main/java/com/dis/fshipbot/ingestion/ExcelContentProcessor.java",
                "src/main/java/com/dis/fshipbot/ingestion/WebContentProcessor.java"}) {
            String content = read(Paths.get(file));
            Assertions.assertTrue(content.contains(NEW_NAME),
                    file + " must query the new table name");
        }
    }

    @Test
    void documentRepositoryFeatureProbeMatchesTheNewTable() throws IOException {
        // The content_tsv existence probe filters on table_name; if it still
        // matched the old name it would always report "absent" and silently
        // downgrade every lexical search to ILIKE.
        String content = read(Paths.get(
                "src", "main", "java", "com", "dis", "fshipbot", "repository", "DocumentRepository.java"));
        Assertions.assertTrue(content.contains("table_name='" + NEW_NAME + "'"),
                "the information_schema probe must look for the new table name");
    }
}
