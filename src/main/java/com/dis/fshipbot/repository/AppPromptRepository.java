package com.dis.fshipbot.repository;

import com.dis.fshipbot.model.AppPrompt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;

@Slf4j
@Repository
public class AppPromptRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final RowMapper<AppPrompt> rowMapper = (rs, rowNum) -> {
        AppPrompt p = new AppPrompt();
        p.setKey(rs.getString("prompt_key"));
        p.setGroup(rs.getString("prompt_group"));
        p.setLabel(rs.getString("label"));
        p.setDescription(rs.getString("description"));
        p.setPlaceholders(splitPlaceholders(rs.getString("placeholders")));
        p.setContent(rs.getString("content"));
        Timestamp ts = rs.getTimestamp("updated_at");
        p.setUpdatedAt(ts != null ? ts.toInstant() : null);
        p.setDefaultText(null);
        p.setCustomized(false);
        return p;
    };

    /** All prompt rows in display order. Empty list when the table has no rows. */
    public List<AppPrompt> findAll() {
        return jdbcTemplate.query(
                "SELECT * FROM fship_ai.app_prompt ORDER BY prompt_group, sort_order, id",
                rowMapper);
    }

    /**
     * The row for this key, or null when it simply has no row yet.
     *
     * <p>Only "no rows" is swallowed. Any other failure (missing table, bad
     * credentials, network) propagates, because collapsing those into null makes
     * an unreachable database indistinguishable from an unseeded one — and the
     * admin then gets told to run {@code data.sql} when the real problem is the
     * connection. PromptService turns that into an actionable message.
     */
    public AppPrompt findByKey(String key) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT * FROM fship_ai.app_prompt WHERE prompt_key = ?", rowMapper, key);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * Upsert a prompt's content. Only {@code content} and {@code updated_at} are
     * written — group/label/description/placeholders are registry-owned metadata
     * and are not editable from the admin panel.
     */
    @Transactional
    public AppPrompt save(String key, String content) {
        jdbcTemplate.update(
                "UPDATE fship_ai.app_prompt SET content = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE prompt_key = ?",
                content, key);
        return findByKey(key);
    }

    public long count() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fship_ai.app_prompt", Long.class);
    }

    private static List<String> splitPlaceholders(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return List.of(raw.split(","));
    }
}
