package com.dis.fshipbot;

import com.dis.fshipbot.model.AppPrompt;
import com.dis.fshipbot.model.PromptSection;
import com.dis.fshipbot.repository.AppPromptRepository;
import com.dis.fshipbot.repository.AppSettingRepository;
import com.dis.fshipbot.service.PromptService;
import com.dis.fshipbot.util.PromptDefaults;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Prompts are DB-driven but must never become a hard dependency: the compiled-in
 * registry in {@link PromptDefaults} is the in-memory fallback, so an unreachable
 * {@code app_prompt} table degrades to defaults instead of breaking chat.
 *
 * <p>These tests use the REAL {@link AppPromptRepository} over a mocked
 * {@link JdbcTemplate} — the behaviour under test IS the repository behaviour for
 * an absent row and for a failed query.
 */
public class PromptServiceTest {

    // ---- Test doubles ----

    /** Seeds are irrelevant here, so the repository is injected without @PostConstruct. */
    private PromptService serviceWith(JdbcTemplate jdbc) {
        AppPromptRepository repo = new AppPromptRepository();
        ReflectionTestUtils.setField(repo, "jdbcTemplate", jdbc);
        PromptService service = new PromptService();
        ReflectionTestUtils.setField(service, "promptRepository", repo);
        return service;
    }

    /** findAll() returns the given rows; save() returns the row for the saved key. */
    private JdbcTemplate jdbcWithRows(Map<String, String> keyToContent) {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        Mockito.when(jdbc.query(
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.<RowMapper<AppPrompt>>any()))
                .thenAnswer(inv -> {
                    List<AppPrompt> rows = new ArrayList<>();
                    for (Map.Entry<String, String> e : keyToContent.entrySet()) {
                        rows.add(row(e.getKey(), e.getValue()));
                    }
                    return rows;
                });
        Mockito.when(jdbc.queryForObject(
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.<RowMapper<AppPrompt>>any(),
                        ArgumentMatchers.<Object[]>any()))
                .thenAnswer(inv -> {
                    for (Object a : invocationArgs(inv)) {
                        if (a instanceof String && keyToContent.containsKey(a)) {
                            return row((String) a, keyToContent.get(a));
                        }
                    }
                    return null;
                });
        return jdbc;
    }

    /**
     * Every query fails, simulating a missing table / DB outage. Both
     * {@code query} (findAll) and {@code queryForObject} (findByKey) must fail:
     * findByKey deliberately no longer swallows real errors, so a stub that only
     * covers findAll would make the save path look like an unseeded table.
     */
    private JdbcTemplate jdbcThrowing() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        IllegalStateException failure =
                new IllegalStateException("relation \"app_prompt\" does not exist");
        Mockito.when(jdbc.query(
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.<RowMapper<AppPrompt>>any()))
                .thenThrow(failure);
        Mockito.when(jdbc.queryForObject(
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.<RowMapper<AppPrompt>>any(),
                        ArgumentMatchers.<Object[]>any()))
                .thenThrow(failure);
        return jdbc;
    }

    /**
     * Mockito flattens varargs differently across versions, so collect every
     * argument and flatten any nested Object[] rather than assuming an index.
     */
    private static List<Object> invocationArgs(org.mockito.invocation.InvocationOnMock inv) {
        List<Object> out = new ArrayList<>();
        for (int i = 2; i < inv.getArguments().length; i++) {
            Object a = inv.getArguments()[i];
            if (a instanceof Object[]) {
                Collections.addAll(out, (Object[]) a);
            } else {
                out.add(a);
            }
        }
        return out;
    }

    private static AppPrompt row(String key, String content) {
        AppPrompt p = new AppPrompt();
        p.setKey(key);
        p.setGroup("chat");
        p.setContent(content);
        p.setUpdatedAt(Instant.now());
        return p;
    }

    private static Map<String, String> single(String key, String content) {
        Map<String, String> m = new HashMap<>();
        m.put(key, content);
        return m;
    }

    // ---- Fallback behaviour ----

    @Test
    public void missingTableFallsBackToRegistryDefaults() {
        PromptService service = serviceWith(jdbcThrowing());
        service.reload();

        for (PromptDefaults.PromptDef def : PromptDefaults.all()) {
            Assertions.assertEquals(def.getDefaultText(), service.get(def.getKey()),
                    "unreachable DB must fall back to the default for " + def.getKey());
        }
    }

    @Test
    public void missingRowForOnePromptFallsBackForThatPromptOnly() {
        Map<String, String> rows = new HashMap<>();
        rows.put(PromptDefaults.CASUAL, "Be a pirate.");
        PromptService service = serviceWith(jdbcWithRows(rows));
        service.reload();

        Assertions.assertEquals("Be a pirate.", service.get(PromptDefaults.CASUAL));
        Assertions.assertEquals(PromptDefaults.defaultText(PromptDefaults.NO_CONTEXT),
                service.get(PromptDefaults.NO_CONTEXT),
                "an absent row must not blank out the rest of the registry");
    }

    @Test
    public void dbValueOverridesDefault() {
        PromptService service = serviceWith(
                jdbcWithRows(single(PromptDefaults.CASUAL, "Be a pirate.")));
        service.reload();

        Assertions.assertEquals("Be a pirate.", service.get(PromptDefaults.CASUAL));
    }

    @Test
    public void blankDbValueFallsBackToDefault() {
        // An admin who clears the box entirely must not blank the prompt at the LLM.
        PromptService service = serviceWith(
                jdbcWithRows(single(PromptDefaults.CASUAL, "   ")));
        service.reload();

        Assertions.assertEquals(PromptDefaults.defaultText(PromptDefaults.CASUAL),
                service.get(PromptDefaults.CASUAL));
    }

    // ---- Rendering ----

    @Test
    public void renderSubstitutesPlaceholders() {
        PromptService service = serviceWith(jdbcWithRows(
                single(PromptDefaults.SYSTEM, "CONTEXT>>{context}<<END")));
        service.reload();

        String rendered = service.render(PromptDefaults.SYSTEM,
                Collections.singletonMap("context", "LEAVE POLICY"));
        Assertions.assertEquals("CONTEXT>>LEAVE POLICY<<END", rendered);
        Assertions.assertFalse(rendered.contains("{context}"),
                "no placeholder may survive rendering");
    }

    @Test
    public void renderReplacesEveryTokenOfMultiPlaceholderPrompt() {
        PromptService service = serviceWith(jdbcThrowing());
        service.reload();

        Map<String, String> tokens = new HashMap<>();
        tokens.put("context", "CTX");
        tokens.put("answer", "ANS");
        tokens.put("question", "QUE");

        String rendered = service.render(PromptDefaults.GROUNDING_VALIDATION, tokens);
        Assertions.assertTrue(rendered.contains("CTX"));
        Assertions.assertTrue(rendered.contains("ANS"));
        Assertions.assertTrue(rendered.contains("QUE"));
        Assertions.assertFalse(rendered.contains("{answer}"));
        Assertions.assertFalse(rendered.contains("{question}"));
    }

    @Test
    public void renderIsLiteralNotRegex() {
        // A replacement value containing regex metacharacters (or a token-looking
        // string) must not corrupt the template or throw.
        PromptService service = serviceWith(jdbcWithRows(
                single(PromptDefaults.SYSTEM, "A={context};B={context}")));
        service.reload();

        String rendered = service.render(PromptDefaults.SYSTEM,
                Collections.singletonMap("context", "$1 \\n {context}"));
        Assertions.assertTrue(rendered.contains("A=$1 \\n {context}"),
                "replace() is literal, so the value is inserted verbatim");
    }

    @Test
    public void unknownKeyReturnsFallbackAndDoesNotThrow() {
        PromptService service = serviceWith(jdbcThrowing());
        service.reload();

        Assertions.assertEquals("fallback", service.get("prompt.does_not_exist", "fallback"));
        Assertions.assertEquals("", service.get("prompt.does_not_exist"));
    }

    // ---- Admin writes ----

    @Test
    public void saveUpdatesTheCacheImmediately() {
        PromptService service = serviceWith(
                jdbcWithRows(single(PromptDefaults.CASUAL, "original")));
        service.reload();

        service.save(PromptDefaults.CASUAL, "edited");

        Assertions.assertEquals("edited", service.get(PromptDefaults.CASUAL),
                "an admin edit must take effect on the very next question");
    }

    @Test
    public void saveRejectsAnUnknownKey() {
        PromptService service = serviceWith(jdbcThrowing());
        service.reload();

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.save("prompt.invented", "text"),
                "only registry keys are writable");
    }

    @Test
    public void saveFailsLoudlyWhenTheRowIsMissing() {
        // Prompts are seeded by data.sql, not by the app. If the row is absent the
        // UPDATE writes nothing, so the edit would be silently lost.
        PromptService service = serviceWith(jdbcThrowing());
        service.reload();

        IllegalStateException e = Assertions.assertThrows(IllegalStateException.class,
                () -> service.save(PromptDefaults.CASUAL, "edited"));
        Assertions.assertTrue(e.getMessage().contains("data.sql"),
                "the error must tell the admin which script to run: " + e.getMessage());
    }

    // ---- Registry invariants ----

    @Test
    public void everyRegistryKeyIsPrefixedAndUnique() {
        Set<String> keys = new HashSet<>();
        for (PromptDefaults.PromptDef def : PromptDefaults.all()) {
            Assertions.assertTrue(def.getKey().startsWith(PromptDefaults.KEY_PREFIX),
                    def.getKey() + " must live under the prompt. prefix");
            Assertions.assertTrue(keys.add(def.getKey()), "duplicate prompt key: " + def.getKey());
            Assertions.assertNotNull(def.getDefaultText());
            Assertions.assertFalse(def.getDefaultText().isBlank());
        }
    }

    @Test
    public void defaultTextsContainTheirDeclaredPlaceholders() {
        // A default that lost its {token} would silently stop injecting context.
        for (PromptDefaults.PromptDef def : PromptDefaults.all()) {
            for (String placeholder : def.getPlaceholders()) {
                Assertions.assertTrue(def.getDefaultText().contains(placeholder),
                        def.getKey() + " default is missing " + placeholder);
            }
        }
    }

    // ---- Sections (the shape the admin page renders) ----

    @Test
    public void listSectionsPreservesRegistryGroupOrder() {
        PromptService service = serviceWith(jdbcThrowing());
        service.reload();

        List<PromptSection> sections = service.listSections();

        List<String> expected = new ArrayList<>(PromptDefaults.groups());
        List<String> actual = new ArrayList<>();
        for (PromptSection s : sections) {
            actual.add(s.getId());
        }
        Assertions.assertEquals(expected, actual, "groups must render in registry order");
    }

    @Test
    public void listSectionsCoversEveryPromptExactlyOnce() {
        PromptService service = serviceWith(jdbcThrowing());
        service.reload();

        Set<String> seen = new HashSet<>();
        int total = 0;
        for (PromptSection s : service.listSections()) {
            Assertions.assertNotNull(s.getTitle(), "group " + s.getId() + " needs a title");
            Assertions.assertNotNull(s.getDescription(), "group " + s.getId() + " needs a description");
            for (AppPrompt p : s.getPrompts()) {
                Assertions.assertTrue(seen.add(p.getKey()),
                        "prompt " + p.getKey() + " appears in more than one section");
                total++;
            }
        }
        Assertions.assertEquals(PromptDefaults.all().size(), total,
                "every prompt must land in exactly one section");
    }

    @Test
    public void listSectionsSkipsEmptyGroups() {
        // A group with no prompts must not render an empty heading.
        PromptService service = serviceWith(jdbcThrowing());
        service.reload();

        for (PromptSection s : service.listSections()) {
            Assertions.assertFalse(s.getPrompts().isEmpty(),
                    "section " + s.getId() + " has no prompts and should be omitted");
        }
    }

    @Test
    public void groupMetadataIsResolvedForEveryGroup() {
        for (String group : PromptDefaults.groups()) {
            Assertions.assertFalse(PromptDefaults.groupTitle(group).isEmpty(),
                    group + " has no title");
            Assertions.assertFalse(PromptDefaults.groupDescription(group).isEmpty(),
                    group + " has no description");
            Assertions.assertNotEquals(PromptDefaults.groupTitle(group),
                    PromptDefaults.groupDescription(group),
                    group + " title and description are the same text — the split is broken");
        }
    }

    @Test
    public void unknownGroupFallsBackToItsId() {
        Assertions.assertEquals("nope", PromptDefaults.groupTitle("nope"));
        Assertions.assertEquals("", PromptDefaults.groupDescription("nope"));
    }

    @Test
    public void groupedListCoversEveryPrompt() {
        PromptService service = serviceWith(jdbcThrowing());
        service.reload();

        Map<String, List<AppPrompt>> grouped = service.listGrouped();
        int total = grouped.values().stream().mapToInt(List::size).sum();
        Assertions.assertEquals(PromptDefaults.all().size(), total);
        for (String group : grouped.keySet()) {
            Assertions.assertTrue(PromptDefaults.groups().contains(group),
                    "unknown group surfaced to the admin UI: " + group);
        }
    }

    // ---- Editability reporting ----

    @Test
    public void missingTableIsReportedAsNotEditable() {
        PromptService service = serviceWith(jdbcThrowing());
        service.reload();

        Assertions.assertFalse(service.isDbBacked(),
                "an unreadable table means edits cannot be saved; the admin page must say so");
        Assertions.assertTrue(service.getUnavailableReason().contains("schema.sql"),
                "the reason must name the script to run: " + service.getUnavailableReason());
    }

    @Test
    public void partiallySeededTableIsReportedWithTheMissingKeys() {
        // Present rows are usable, but the gaps are not — and the admin needs to
        // know exactly which prompts cannot be saved.
        PromptService service = serviceWith(
                jdbcWithRows(single(PromptDefaults.SYSTEM, "present")));
        service.reload();

        Assertions.assertFalse(service.isDbBacked());
        String reason = service.getUnavailableReason();
        Assertions.assertTrue(reason.contains(PromptDefaults.CASUAL), reason);
        Assertions.assertTrue(reason.contains(PromptDefaults.NO_CONTEXT), reason);
        Assertions.assertFalse(reason.contains(PromptDefaults.SYSTEM),
                "a prompt that IS seeded must not be listed as missing: " + reason);
    }

    @Test
    public void fullySeededTableIsReportedAsEditable() {
        Map<String, String> all = new HashMap<>();
        for (PromptDefaults.PromptDef def : PromptDefaults.all()) {
            all.put(def.getKey(), def.getDefaultText());
        }
        PromptService service = serviceWith(jdbcWithRows(all));
        service.reload();

        Assertions.assertTrue(service.isDbBacked(),
                "a fully seeded table must allow edits");
        Assertions.assertEquals("", service.getUnavailableReason());
    }

    @Test
    public void saveReportsAnUnreachableTableWithTheScriptName() {
        PromptService service = serviceWith(jdbcThrowing());
        service.reload();

        IllegalStateException e = Assertions.assertThrows(IllegalStateException.class,
                () -> service.save(PromptDefaults.CASUAL, "edited"));
        Assertions.assertTrue(e.getMessage().contains("schema.sql"),
                "the error must tell the admin which script to run: " + e.getMessage());
    }

    @Test
    public void listAllFlagsCustomizedPrompts() {
        PromptService service = serviceWith(
                jdbcWithRows(single(PromptDefaults.FOUL_LANGUAGE, "custom reply")));
        service.reload();

        AppPrompt edited = service.listAll().stream()
                .filter(p -> PromptDefaults.FOUL_LANGUAGE.equals(p.getKey()))
                .findFirst()
                .orElseThrow(AssertionError::new);

        Assertions.assertTrue(edited.isCustomized());
        Assertions.assertEquals("custom reply", edited.getContent());
        Assertions.assertEquals(PromptDefaults.defaultText(PromptDefaults.FOUL_LANGUAGE),
                edited.getDefaultText());
    }

    @Test
    public void listAllMarksUntouchedPromptsAsDefault() {
        PromptService service = serviceWith(jdbcThrowing());
        service.reload();

        for (AppPrompt p : service.listAll()) {
            Assertions.assertFalse(p.isCustomized(),
                    p.getKey() + " matches its default, so it must not be badged");
        }
    }
}
