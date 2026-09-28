package com.dis.fshipbot.service;

import com.dis.fshipbot.model.AppSetting;
import com.dis.fshipbot.model.AppSuggestion;
import com.dis.fshipbot.repository.AppSettingRepository;
import com.dis.fshipbot.repository.AppSuggestionRepository;
import com.dis.fshipbot.util.PromptDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AppConfigService {

    @Autowired
    private AppSettingRepository settingRepo;

    @Autowired
    private AppSuggestionRepository suggestionRepo;

    // ---- Settings ----

    public List<AppSetting> getAllSettings() {
        return settingRepo.findAll();
    }

    public List<AppSetting> getSettingsByCategory(String category) {
        return settingRepo.findByCategory(category);
    }

    public String getSetting(String key, String defaultValue) {
        return settingRepo.getValue(key, defaultValue);
    }

    public AppSetting updateSetting(String key, String value, String category) {
        return settingRepo.upsert(key, value, category);
    }

    public void deleteSetting(String key) {
        settingRepo.delete(key);
    }

    // ---- Suggestions ----

    public List<AppSuggestion> getAllSuggestions() {
        return suggestionRepo.findAll();
    }

    public List<AppSuggestion> getActiveSuggestions() {
        return suggestionRepo.findActive();
    }

    public AppSuggestion getSuggestion(Long id) {
        return suggestionRepo.findById(id);
    }

    public AppSuggestion addSuggestion(AppSuggestion s) {
        if (s.getSortOrder() == null || s.getSortOrder() == 0) {
            s.setSortOrder(suggestionRepo.nextSortOrder());
        }
        return suggestionRepo.save(s);
    }

    public AppSuggestion updateSuggestion(Long id, AppSuggestion s) {
        return suggestionRepo.update(id, s);
    }

    public void toggleSuggestion(Long id) {
        suggestionRepo.toggleActive(id);
    }

    public void deleteSuggestion(Long id) {
        suggestionRepo.delete(id);
    }

    public void reorderSuggestion(Long id, int direction) {
        AppSuggestion neighbor = suggestionRepo.findNeighbor(id, direction);
        if (neighbor != null) {
            suggestionRepo.swapSortOrder(id, neighbor.getId());
        }
    }

    // ---- XML Builder ----

    public String buildXml() {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<app>\n");

        // Settings by category.
        // 'prompt' rows are EXCLUDED: this XML is served unauthenticated to the chat
        // UI, and system prompts must never be exposed there. They are managed via
        // the session-gated /admin/api/prompts endpoints instead.
        List<AppSetting> settings = settingRepo.findAll().stream()
                .filter(s -> !PromptDefaults.CATEGORY.equals(s.getCategory()))
                .collect(Collectors.toList());
        String lastCategory = "";

        for (AppSetting s : settings) {
            if (!s.getCategory().equals(lastCategory)) {
                if (!lastCategory.isEmpty()) xml.append("    </").append(lastCategory).append(">\n");
                xml.append("    <").append(s.getCategory()).append(">\n");
                lastCategory = s.getCategory();
            }
            xml.append("        <").append(s.getConfigKey().replace(s.getCategory() + ".", "")).append(">")
               .append(escapeXml(s.getConfigValue())).append("</").append(s.getConfigKey().replace(s.getCategory() + ".", "")).append(">\n");
        }
        if (!lastCategory.isEmpty()) xml.append("    </").append(lastCategory).append(">\n");

        // Suggestions
        List<AppSuggestion> suggestions = suggestionRepo.findActive();
        xml.append("    <suggestions>\n");
        for (AppSuggestion s : suggestions) {
            xml.append("        <suggestion>\n");
            xml.append("            <question>").append(escapeXml(s.getQuestion())).append("</question>\n");
            xml.append("            <caption>").append(escapeXml(s.getCaption())).append("</caption>\n");
            xml.append("            <icon>").append(escapeXml(s.getIcon())).append("</icon>\n");
            xml.append("        </suggestion>\n");
        }
        xml.append("    </suggestions>\n");

        xml.append("</app>\n");
        return xml.toString();
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
