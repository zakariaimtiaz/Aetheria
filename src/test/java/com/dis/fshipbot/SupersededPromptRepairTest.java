package com.dis.fshipbot;

import com.dis.fshipbot.model.AppPrompt;
import com.dis.fshipbot.service.PromptService;
import com.dis.fshipbot.util.PromptDefaults;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Corrected prompt defaults must reach databases that were already seeded.
 *
 * <p>{@code db/data.sql} is idempotent per key so admin edits survive, which
 * means it can never deliver a corrected default to an existing row. Without a
 * repair step the grounding-criteria fix would apply to fresh databases only,
 * and the production one would silently keep the wording that caused the bug.
 *
 * <p>The repair must be surgical: it may only replace text it recognises as a
 * known-bad default. Anything else is an admin's own wording and is off limits.
 */
public class SupersededPromptRepairTest {

    private PromptService serviceWith(JdbcTemplate jdbc) {
        var repo = new com.dis.fshipbot.repository.AppPromptRepository();
        ReflectionTestUtils.setField(repo, "jdbcTemplate", jdbc);
        var service = new PromptService();
        ReflectionTestUtils.setField(service, "promptRepository", repo);
        return service;
    }

    private JdbcTemplate jdbcWith(Map<String, String> rows) {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        Mockito.when(jdbc.query(ArgumentMatchers.anyString(),
                        ArgumentMatchers.<RowMapper<AppPrompt>>any()))
                .thenAnswer(inv -> {
                    java.util.List<AppPrompt> out = new java.util.ArrayList<>();
                    rows.forEach((k, v) -> {
                        AppPrompt p = new AppPrompt();
                        p.setKey(k);
                        p.setContent(v);
                        out.add(p);
                    });
                    return out;
                });
        return jdbc;
    }

    private JdbcTemplate jdbcFailingWrites() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        Mockito.when(jdbc.update(ArgumentMatchers.anyString(),
                        ArgumentMatchers.any(Object[].class)))
                .thenThrow(new IllegalStateException("connection reset"));
        return jdbc;
    }

    /** The wording that shipped before the fix, reconstructed from the history. */
    private static final String OLD_TRUE =
            "Every fact, number, name, date, rule, and list item in the answer "
                    + "is supported by the document context, even when reworded, "
                    + "summarized across chunks, or restated rather than quoted verbatim.";

    private static final String OLD_FALSE =
            "The answer contains facts, numbers, names, or details that "
                    + "contradict the context or cannot be found in it at all. "
                    + "Mere rewording or summarizing of context facts still counts as "
                    + "supported and must NOT fail. "
                    + "Explicit statements that information is unavailable do NOT count "
                    + "against grounding; only factual claims are judged.";

    @Test
    void oldGroundingWordingIsRecognisedAsSuperseded() {
        Assertions.assertTrue(PromptDefaults.isSuperseded(PromptDefaults.JEV_GROUNDING_TRUE, OLD_TRUE),
                "the shipped wording must be recognised, or the fix never reaches a seeded database");
        Assertions.assertTrue(
                PromptDefaults.isSuperseded(PromptDefaults.JEV_GROUNDING_FALSE, OLD_FALSE));
    }

    @Test
    void adminEditsAreNotMistakenForSupersededText() {
        Assertions.assertFalse(PromptDefaults.isSuperseded(
                        PromptDefaults.JEV_GROUNDING_TRUE, "Our house rules say otherwise"),
                "custom wording must never be flagged for replacement");
        Assertions.assertFalse(PromptDefaults.isSuperseded(
                        PromptDefaults.JEV_GROUNDING_TRUE, OLD_TRUE + " (ours)"),
                "text merely containing the old wording is an admin edit");
        Assertions.assertFalse(PromptDefaults.isSuperseded(
                        PromptDefaults.JEV_GROUNDING_TRUE, null));
    }

    @Test
    void supersededWordingIsRepairedOnLoad() {
        Map<String, String> rows = new HashMap<>();
        rows.put(PromptDefaults.JEV_GROUNDING_TRUE, OLD_TRUE);

        PromptService service = serviceWith(jdbcWith(rows));
        service.reload();

        Assertions.assertEquals(
                PromptDefaults.defaultText(PromptDefaults.JEV_GROUNDING_TRUE),
                service.get(PromptDefaults.JEV_GROUNDING_TRUE),
                "the corrected criteria must be what the grounding judge reads");
    }

    @Test
    void repairIsPersistedSoItHappensOnce() {
        Map<String, String> rows = new HashMap<>();
        rows.put(PromptDefaults.JEV_GROUNDING_TRUE, OLD_TRUE);
        JdbcTemplate jdbc = jdbcWith(rows);

        serviceWith(jdbc).reload();

        Mockito.verify(jdbc).update(Mockito.contains("fship_ai.app_prompt"),
                ArgumentMatchers.eq(PromptDefaults.defaultText(PromptDefaults.JEV_GROUNDING_TRUE)),
                ArgumentMatchers.eq(PromptDefaults.JEV_GROUNDING_TRUE));
    }

    @Test
    void anUnwritableDatabaseStillGetsTheCorrectedTextInMemory() {
        // A locked-down or read-only database must degrade to correct behaviour,
        // not fall back to the known-bad wording.
        Map<String, String> rows = new HashMap<>();
        rows.put(PromptDefaults.JEV_GROUNDING_TRUE, OLD_TRUE);
        JdbcTemplate jdbc = jdbcWith(rows);
        Mockito.when(jdbc.update(ArgumentMatchers.anyString(),
                        ArgumentMatchers.any(Object[].class)))
                .thenThrow(new IllegalStateException("read-only"));

        PromptService service = serviceWith(jdbc);
        service.reload();

        Assertions.assertEquals(
                PromptDefaults.defaultText(PromptDefaults.JEV_GROUNDING_TRUE),
                service.get(PromptDefaults.JEV_GROUNDING_TRUE),
                "a failed repair write must not resurrect the known-bad text");
    }

    @Test
    void unchangedPromptsAreNotRewritten() {
        // Only the two known-bad rows may be touched; a normal load must not
        // issue any write at all.
        Map<String, String> rows = new HashMap<>();
        rows.put(PromptDefaults.JEV_GROUNDING_TRUE,
                PromptDefaults.defaultText(PromptDefaults.JEV_GROUNDING_TRUE));
        rows.put(PromptDefaults.CASUAL, "an admin's own wording");
        JdbcTemplate jdbc = jdbcWith(rows);

        serviceWith(jdbc).reload();

        Mockito.verify(jdbc, Mockito.never())
                .update(Mockito.anyString(), ArgumentMatchers.any(Object[].class));
    }
}
