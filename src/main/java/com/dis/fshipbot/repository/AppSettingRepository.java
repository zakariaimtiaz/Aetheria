package com.dis.fshipbot.repository;

import com.dis.fshipbot.model.AppSetting;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Slf4j
@Repository
public class AppSettingRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final RowMapper<AppSetting> rowMapper = (rs, rowNum) -> {
        AppSetting s = new AppSetting();
        s.setId(rs.getLong("id"));
        s.setConfigKey(rs.getString("config_key"));
        s.setConfigValue(rs.getString("config_value"));
        s.setCategory(rs.getString("category"));
        s.setUpdatedAt(rs.getTimestamp("updated_at") != null
                ? rs.getTimestamp("updated_at").toInstant() : null);
        return s;
    };

    public List<AppSetting> findAll() {
        return jdbcTemplate.query("SELECT * FROM fship_ai.app_setting ORDER BY category, config_key", rowMapper);
    }

    public List<AppSetting> findByCategory(String category) {
        return jdbcTemplate.query("SELECT * FROM fship_ai.app_setting WHERE category = ? ORDER BY config_key",
                rowMapper, category);
    }

    public AppSetting findByKey(String key) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT * FROM fship_ai.app_setting WHERE config_key = ?", rowMapper, key);
        } catch (Exception e) {
            return null;
        }
    }

    public String getValue(String key, String defaultValue) {
        AppSetting s = findByKey(key);
        return s != null ? s.getConfigValue() : defaultValue;
    }

    @Transactional
    public AppSetting upsert(String key, String value, String category) {
        int rows = jdbcTemplate.update(
                "UPDATE fship_ai.app_setting SET config_value = ?, category = ?, updated_at = CURRENT_TIMESTAMP WHERE config_key = ?",
                value, category, key);
        if (rows == 0) {
            jdbcTemplate.update(
                    "INSERT INTO fship_ai.app_setting (config_key, config_value, category) VALUES (?, ?, ?)",
                    key, value, category);
        }
        return findByKey(key);
    }

    @Transactional
    public void delete(String key) {
        jdbcTemplate.update("DELETE FROM fship_ai.app_setting WHERE config_key = ?", key);
    }

    public long count() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fship_ai.app_setting", Long.class);
    }
}
