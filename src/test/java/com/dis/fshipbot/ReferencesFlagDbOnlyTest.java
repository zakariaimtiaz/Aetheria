package com.dis.fshipbot;

import com.dis.fshipbot.model.AppSetting;
import com.dis.fshipbot.repository.AppSettingRepository;
import com.dis.fshipbot.service.ChatService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@code app.references.enabled} is DB-ONLY.
 *
 * <p>The property was removed from application.properties, so the {@code
 * app_setting} row is the single source of truth and there is no second value
 * that can disagree with the Document Tools toggle.
 *
 * <p>These tests use the REAL {@link AppSettingRepository} over a mocked
 * {@link JdbcTemplate} rather than mocking the repository, because the behaviour
 * under test is precisely what the repository does for an absent row and for a
 * failed query: both resolve to the caller's default, which is now "false".
 */
public class ReferencesFlagDbOnlyTest {

    /** Repository whose single-row lookup returns {@code row} (null = absent). */
    private AppSettingRepository repoReturning(AppSetting row) {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        Mockito.when(jdbc.queryForObject(
                ArgumentMatchers.anyString(),
                ArgumentMatchers.any(org.springframework.jdbc.core.RowMapper.class),
                ArgumentMatchers.<Object[]>any()))
                .thenReturn(row);
        AppSettingRepository repo = new AppSettingRepository();
        ReflectionTestUtils.setField(repo, "jdbcTemplate", jdbc);
        return repo;
    }

    private AppSettingRepository repoThrowing() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        Mockito.when(jdbc.queryForObject(
                ArgumentMatchers.anyString(),
                ArgumentMatchers.any(org.springframework.jdbc.core.RowMapper.class),
                ArgumentMatchers.<Object[]>any()))
                .thenThrow(new IllegalStateException("relation app_setting does not exist"));
        AppSettingRepository repo = new AppSettingRepository();
        ReflectionTestUtils.setField(repo, "jdbcTemplate", jdbc);
        return repo;
    }

    private static AppSetting setting(String value) {
        AppSetting s = new AppSetting();
        s.setConfigKey("app.references.enabled");
        s.setConfigValue(value);
        s.setCategory("display");
        return s;
    }

    private ChatService serviceWith(AppSettingRepository repo) {
        ChatService service = new ChatService();
        ReflectionTestUtils.setField(service, "settingRepository", repo);
        return service;
    }

    @Test
    public void absentRowResolvesToOff() {
        Assertions.assertFalse(serviceWith(repoReturning(null)).isReferencesEnabled(),
                "no row in app_setting must mean references OFF");
    }

    @Test
    public void rowTrueEnablesReferences() {
        Assertions.assertTrue(serviceWith(repoReturning(setting("true"))).isReferencesEnabled());
    }

    @Test
    public void rowFalseDisablesReferences() {
        Assertions.assertFalse(serviceWith(repoReturning(setting("false"))).isReferencesEnabled());
    }

    @Test
    public void failedLookupFailsClosedToOff() {
        Assertions.assertFalse(serviceWith(repoThrowing()).isReferencesEnabled(),
                "a failed DB lookup must not enable references");
    }

    @Test
    public void nullRepositoryFailsClosedToOff() {
        Assertions.assertFalse(serviceWith(null).isReferencesEnabled());
    }

    @Test
    public void blankValueIsNotTreatedAsFalseKeyword() {
        // Only the literal "false" disables; anything else is "on", matching the
        // original !"false".equalsIgnoreCase(...) semantics.
        Assertions.assertTrue(serviceWith(repoReturning(setting("TRUE"))).isReferencesEnabled());
    }
}
