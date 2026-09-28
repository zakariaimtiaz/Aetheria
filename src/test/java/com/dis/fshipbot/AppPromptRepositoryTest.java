package com.dis.fshipbot;

import com.dis.fshipbot.model.AppPrompt;
import com.dis.fshipbot.repository.AppPromptRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

/**
 * An admin edit must reach PostgreSQL, not just the in-memory cache.
 *
 * <p>The cache is what the chat pipeline reads, so a save that only updated
 * memory would look correct in the UI and vanish on the next restart. These
 * tests assert the actual SQL issued against {@code fship_ai.app_prompt}, which
 * is the only way to catch a repository that silently stops writing.
 */
public class AppPromptRepositoryTest {

    private JdbcTemplate jdbc;
    private AppPromptRepository repo;

    @BeforeEach
    void setUp() {
        jdbc = Mockito.mock(JdbcTemplate.class);
        repo = new AppPromptRepository();
        ReflectionTestUtils.setField(repo, "jdbcTemplate", jdbc);
    }

    private void stubFindByKey(String key, String content) {
        Mockito.when(jdbc.queryForObject(
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.<RowMapper<AppPrompt>>any(),
                        ArgumentMatchers.<Object[]>any()))
                .thenAnswer(inv -> {
                    for (int i = 2; i < inv.getArguments().length; i++) {
                        Object a = inv.getArguments()[i];
                        if (a instanceof Object[]) {
                            for (Object nested : (Object[]) a) {
                                if (key.equals(nested)) {
                                    return row(key, content);
                                }
                            }
                        } else if (key.equals(a)) {
                            return row(key, content);
                        }
                    }
                    return null;
                });
    }

    private static AppPrompt row(String key, String content) {
        AppPrompt p = new AppPrompt();
        p.setKey(key);
        p.setContent(content);
        p.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return p;
    }

    @Test
    void saveUpdatesTheContentColumnOfThePromptTable() {
        stubFindByKey("prompt.system", "edited text");

        repo.save("prompt.system", "edited text");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        Mockito.verify(jdbc).update(sql.capture(),
                ArgumentMatchers.eq("edited text"),
                ArgumentMatchers.eq("prompt.system"));

        Assertions.assertTrue(sql.getValue().contains("UPDATE fship_ai.app_prompt"),
                "the write must target the prompt table: " + sql.getValue());
        Assertions.assertTrue(sql.getValue().contains("SET content = ?"),
                "only the content column is editable: " + sql.getValue());
        Assertions.assertTrue(sql.getValue().contains("WHERE prompt_key = ?"),
                "the write must be keyed on prompt_key: " + sql.getValue());
    }

    @Test
    void saveStampsTheUpdateTime() {
        stubFindByKey("prompt.system", "x");

        repo.save("prompt.system", "x");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        // Typed matchers, not any(): a bare any() on this overload resolves to a
        // different JdbcTemplate.update method and the verify matches nothing.
        Mockito.verify(jdbc).update(sql.capture(),
                ArgumentMatchers.eq("x"),
                ArgumentMatchers.eq("prompt.system"));
        Assertions.assertTrue(sql.getValue().contains("updated_at = CURRENT_TIMESTAMP"),
                "an edit without a timestamp cannot be shown as 'updated' in the admin UI");
    }

    @Test
    void saveReturnsThePersistedRow() {
        stubFindByKey("prompt.casual", "brand new text");

        AppPrompt saved = repo.save("prompt.casual", "brand new text");

        Assertions.assertNotNull(saved, "the saved row must be returned so the UI shows what landed");
        Assertions.assertEquals("brand new text", saved.getContent());
        Assertions.assertNotNull(saved.getUpdatedAt());
    }

    @Test
    void findAllOrdersByGroupThenSortOrder() {
        Mockito.when(jdbc.query(ArgumentMatchers.anyString(),
                        ArgumentMatchers.<RowMapper<AppPrompt>>any()))
                .thenReturn(List.of());

        repo.findAll();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        Mockito.verify(jdbc).query(sql.capture(), ArgumentMatchers.any(RowMapper.class));
        Assertions.assertTrue(sql.getValue().contains("ORDER BY prompt_group, sort_order"),
                "the admin grid renders groups in registry order: " + sql.getValue());
    }

    @Test
    void findByKeyReturnsNullForAnAbsentRow() {
        Mockito.when(jdbc.queryForObject(
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.<RowMapper<AppPrompt>>any(),
                        ArgumentMatchers.<Object[]>any()))
                .thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));

        Assertions.assertNull(repo.findByKey("prompt.missing"),
                "an absent row must be distinguishable from a present one");
    }

    @Test
    void findByKeyPropagatesARealDatabaseFailure() {
        // Swallowing this as "no row" would make an unreachable database look
        // like an unseeded one, and the admin would be told to run data.sql
        // instead of fixing the connection.
        Mockito.when(jdbc.queryForObject(
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.<RowMapper<AppPrompt>>any(),
                        ArgumentMatchers.<Object[]>any()))
                .thenThrow(new org.springframework.jdbc.BadSqlGrammarException(
                        "SELECT * FROM fship_ai.app_prompt WHERE prompt_key = ?",
                        "Schema \"fship_ai\" not found",
                        new java.sql.SQLException("Schema \"fship_ai\" not found")));

        Assertions.assertThrows(org.springframework.jdbc.BadSqlGrammarException.class,
                () -> repo.findByKey("prompt.system"),
                "a genuine query failure must not be reported as a missing row");
    }

    @Test
    void saveNeverInsertsARow() {
        stubFindByKey("prompt.system", "x");

        repo.save("prompt.system", "x");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        Mockito.verify(jdbc).update(sql.capture(),
                ArgumentMatchers.eq("x"),
                ArgumentMatchers.eq("prompt.system"));
        Assertions.assertFalse(sql.getValue().toUpperCase().contains("INSERT"),
                "prompts are seeded by data.sql; the app must never insert them");
    }
}
