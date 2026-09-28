package com.dis.fshipbot.repository;

import com.dis.fshipbot.model.AppSuggestion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.util.List;

@Slf4j
@Repository
public class AppSuggestionRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final RowMapper<AppSuggestion> rowMapper = (rs, rowNum) -> {
        AppSuggestion s = new AppSuggestion();
        s.setId(rs.getLong("id"));
        s.setQuestion(rs.getString("question"));
        s.setCaption(rs.getString("caption"));
        s.setIcon(rs.getString("icon"));
        s.setSortOrder(rs.getInt("sort_order"));
        s.setIsActive(rs.getBoolean("is_active"));
        s.setCreatedAt(rs.getTimestamp("created_at") != null
                ? rs.getTimestamp("created_at").toInstant() : null);
        s.setUpdatedAt(rs.getTimestamp("updated_at") != null
                ? rs.getTimestamp("updated_at").toInstant() : null);
        return s;
    };

    public List<AppSuggestion> findAll() {
        return jdbcTemplate.query(
                "SELECT * FROM fship_ai.app_suggestion ORDER BY sort_order, id", rowMapper);
    }

    public List<AppSuggestion> findActive() {
        return jdbcTemplate.query(
                "SELECT * FROM fship_ai.app_suggestion WHERE is_active = true ORDER BY sort_order, id",
                rowMapper);
    }

    public AppSuggestion findById(Long id) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT * FROM fship_ai.app_suggestion WHERE id = ?", rowMapper, id);
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional
    public AppSuggestion save(AppSuggestion s) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO fship_ai.app_suggestion (question, caption, icon, sort_order, is_active) VALUES (?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, s.getQuestion());
            ps.setString(2, s.getCaption());
            ps.setString(3, s.getIcon());
            ps.setInt(4, s.getSortOrder() != null ? s.getSortOrder() : 0);
            ps.setBoolean(5, s.getIsActive() != null ? s.getIsActive() : true);
            return ps;
        }, keyHolder);
        return findById(keyHolder.getKey().longValue());
    }

    @Transactional
    public AppSuggestion update(Long id, AppSuggestion s) {
        jdbcTemplate.update(
                "UPDATE fship_ai.app_suggestion SET question = ?, caption = ?, icon = ?, sort_order = ?, is_active = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                s.getQuestion(), s.getCaption(), s.getIcon(),
                s.getSortOrder(), s.getIsActive(), id);
        return findById(id);
    }

    @Transactional
    public void toggleActive(Long id) {
        jdbcTemplate.update(
                "UPDATE fship_ai.app_suggestion SET is_active = NOT is_active, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                id);
    }

    @Transactional
    public void delete(Long id) {
        jdbcTemplate.update("DELETE FROM fship_ai.app_suggestion WHERE id = ?", id);
    }

    /**
     * Swap sort_order between two suggestions (for up/down reordering).
     */
    @Transactional
    public void swapSortOrder(Long id1, Long id2) {
        AppSuggestion s1 = findById(id1);
        AppSuggestion s2 = findById(id2);
        if (s1 == null || s2 == null) return;

        int tmp = s1.getSortOrder();
        jdbcTemplate.update(
                "UPDATE fship_ai.app_suggestion SET sort_order = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                s2.getSortOrder(), id1);
        jdbcTemplate.update(
                "UPDATE fship_ai.app_suggestion SET sort_order = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                tmp, id2);
    }

    /**
     * Get the suggestion immediately before or after the given one (by sort_order).
     * direction: -1 = previous (up), +1 = next (down)
     */
    public AppSuggestion findNeighbor(Long id, int direction) {
        AppSuggestion current = findById(id);
        if (current == null) return null;

        if (direction < 0) {
            // Find the one with the largest sort_order that is still less than current
            try {
                return jdbcTemplate.queryForObject(
                        "SELECT * FROM fship_ai.app_suggestion WHERE sort_order < ? ORDER BY sort_order DESC LIMIT 1",
                        rowMapper, current.getSortOrder());
            } catch (Exception e) {
                return null;
            }
        } else {
            // Find the one with the smallest sort_order that is still greater than current
            try {
                return jdbcTemplate.queryForObject(
                        "SELECT * FROM fship_ai.app_suggestion WHERE sort_order > ? ORDER BY sort_order ASC LIMIT 1",
                        rowMapper, current.getSortOrder());
            } catch (Exception e) {
                return null;
            }
        }
    }

    public int nextSortOrder() {
        try {
            Integer max = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(sort_order), 0) FROM fship_ai.app_suggestion", Integer.class);
            return (max != null ? max : 0) + 1;
        } catch (Exception e) {
            return 1;
        }
    }
}
