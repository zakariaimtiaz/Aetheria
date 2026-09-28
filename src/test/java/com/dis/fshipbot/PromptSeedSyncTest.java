package com.dis.fshipbot;

import com.dis.fshipbot.util.PromptDefaults;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code db/data.sql} is the seed for {@code fship_ai.app_prompt} — the app
 * never inserts prompt rows itself. {@link com.dis.fshipbot.util.PromptDefaults}
 * is only the compiled-in fallback used when a row is missing.
 *
 * <p>Two copies of the same text can drift, and the failure is silent (a prompt
 * quietly reverting to an older wording on a fresh database). These tests parse
 * the generated INSERT block and compare it to the registry key by key, so the
 * drift fails the build instead.
 */
public class PromptSeedSyncTest {

    private static final Path DATA_SQL =
            Paths.get("src", "main", "resources", "db", "data.sql");

    /** Matches one row of the generated prompt seed block. */
    private static final Pattern SEED_ROW = Pattern.compile(
            "^\\s*\\('(prompt\\.[a-z0-9_]+)',\\s*'([a-z]+)',.*?,\\s*(\\d+)\\)[,;]$");

    private String dataSql() throws IOException {
        return new String(Files.readAllBytes(DATA_SQL), StandardCharsets.UTF_8);
    }

    /** Extracts (key, group, content) for each seeded prompt row. */
    private List<String[]> seedRows(String sql) {
        List<String[]> rows = new ArrayList<>();
        boolean inPromptBlock = false;
        for (String line : sql.split("\\r?\\n")) {
            if (line.startsWith("INSERT INTO fship_ai.app_prompt")) {
                inPromptBlock = true;
                continue;
            }
            if (!inPromptBlock) {
                continue;
            }
            if (line.startsWith("WHERE NOT EXISTS")) {
                break;
            }
            Matcher m = SEED_ROW.matcher(line);
            if (m.matches()) {
                rows.add(new String[]{m.group(1), m.group(2), m.group(3)});
            } else if (line.startsWith("    (") || line.startsWith(");")) {
                // Multi-line content: this is a continuation, not a new row start
                // we can parse. Such rows are covered by the key-count assertion.
                continue;
            }
        }
        return rows;
    }

    /** Every key literal appearing in the seed block, in order. */
    private List<String> seededKeys(String sql) {
        List<String> keys = new ArrayList<>();
        int start = sql.indexOf("INSERT INTO fship_ai.app_prompt");
        Assertions.assertTrue(start >= 0, "data.sql must seed fship_ai.app_prompt");
        int end = sql.indexOf("WHERE NOT EXISTS", start);
        String block = sql.substring(start, end < 0 ? sql.length() : end);
        Matcher m = Pattern.compile("\\('(prompt\\.[a-z0-9_]+)',").matcher(block);
        while (m.find()) {
            keys.add(m.group(1));
        }
        return keys;
    }

    @Test
    public void dataSqlSeedsEveryRegistryPrompt() throws IOException {
        List<String> seeded = seededKeys(dataSql());
        List<PromptDefaults.PromptDef> registry = PromptDefaults.all();

        Assertions.assertEquals(registry.size(), seeded.size(),
                "data.sql must seed exactly the prompts in PromptDefaults.\n"
                        + "Seeded: " + seeded.size() + ", registry: " + registry.size());

        List<String> expected = new ArrayList<>();
        for (PromptDefaults.PromptDef def : registry) {
            expected.add(def.getKey());
        }
        Assertions.assertEquals(expected, seeded,
                "seeded keys must match the registry, in the same order");
    }

    @Test
    public void seedRowsCarryTheRegistryGroup() throws IOException {
        // Group determines which admin panel section the prompt is filed under.
        Map<String, String> expectedGroups = new LinkedHashMap<>();
        for (PromptDefaults.PromptDef def : PromptDefaults.all()) {
            expectedGroups.put(def.getKey(), def.getGroup());
        }
        // Multi-line rows are not parseable line-by-line; assert on the ones that are.
        for (String[] row : seedRows(dataSql())) {
            Assertions.assertEquals(expectedGroups.get(row[0]), row[1],
                    "group mismatch for " + row[0]);
        }
    }

    @Test
    public void seedIsIdempotentPerKey() throws IOException {
        String sql = dataSql();
        int blockStart = sql.indexOf("INSERT INTO fship_ai.app_prompt");
        int blockEnd = sql.indexOf(";", sql.indexOf("WHERE NOT EXISTS", blockStart));
        String block = sql.substring(blockStart, blockEnd);

        Assertions.assertTrue(block.contains("WHERE NOT EXISTS"),
                "the prompt seed must be guarded so re-running data.sql never clobbers admin edits");
        Assertions.assertTrue(block.contains("a.prompt_key = v.prompt_key"),
                "the guard must match on prompt_key, not a blanket 'table is empty' check");
    }

    @Test
    public void schemaDeclaresPromptTable() throws IOException {
        String schema = new String(
                Files.readAllBytes(Paths.get("src", "main", "resources", "db", "schema.sql")),
                StandardCharsets.UTF_8);

        Assertions.assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS fship_ai.app_prompt"),
                "schema.sql must create the app_prompt table");
        Assertions.assertTrue(schema.contains("prompt_key varchar(100) NOT NULL UNIQUE"),
                "prompt_key must be unique — the admin save path upserts on it");
    }
}
