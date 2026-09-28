package com.dis.fshipbot.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * A row of {@code fship_ai.app_prompt} — the admin-editable prompt store seeded
 * by {@code db/data.sql}. Label/description/placeholders come from the registry
 * in {@link com.dis.fshipbot.util.PromptDefaults} so the admin UI can render an
 * edit form without a second lookup; {@code content} is the live text actually
 * sent to the model.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AppPrompt {
    private String key;
    private String group;
    private String groupTitle;
    private String label;
    private String description;
    private List<String> placeholders;
    private String content;
    private String defaultText;
    private boolean customized;
    private Instant updatedAt;
}
