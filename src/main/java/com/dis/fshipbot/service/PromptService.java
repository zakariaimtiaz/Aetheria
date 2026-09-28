package com.dis.fshipbot.service;

import com.dis.fshipbot.model.AppPrompt;
import com.dis.fshipbot.model.PromptSection;
import com.dis.fshipbot.repository.AppPromptRepository;
import com.dis.fshipbot.util.PromptDefaults;
import com.dis.fshipbot.util.PromptDefaults.PromptDef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single access point for every LLM prompt in the application.
 *
 * <p>Prompts live in {@code fship_ai.app_setting} under
 * {@code category = 'prompt'} with keys {@code prompt.*}. The registry in
 * {@link PromptDefaults} is the seed and the in-memory fallback, so the bot
 * still answers correctly if the DB is unreachable (tests, first boot before
 * schema exists) — it simply runs on defaults.
 *
 * <p>Values are cached in a {@link ConcurrentHashMap} and reloaded on every
 * admin write, so an admin edit takes effect on the next question without a
 * restart.
 */
@Slf4j
@Service
public class PromptService {

    @Autowired
    private AppPromptRepository promptRepository;

    /** key -> prompt text (DB value when present, otherwise the registry default). */
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /** key -> last updated timestamp, for the admin UI. */
    private final Map<String, Instant> updatedAt = new ConcurrentHashMap<>();

    /**
     * True when every prompt is being served from the database. False means the
     * {@code app_prompt} table is missing or unseeded and the compiled-in
     * registry defaults are in use — in which case admin edits cannot be saved.
     * Surfaced on the Prompts page so the cause is visible instead of every
     * save failing with an unexplained error.
     */
    private volatile boolean dbBacked = true;

    private volatile String unavailableReason = "";

    public boolean isDbBacked() {
        return dbBacked;
    }

    public String getUnavailableReason() {
        return unavailableReason;
    }

    @PostConstruct
    public void init() {
        reload();
    }

    /**
     * Re-read every prompt from {@code fship_ai.app_prompt}.
     *
     * <p>Rows are seeded by {@code db/data.sql}, never written from Java at
     * startup. A missing table (script not run) or a missing row falls back to
     * the compiled-in registry default, so the bot always has usable prompts —
     * but the bot is then NOT editable, which {@link #isDbBacked()} reports.
     */
    public void reload() {
        cache.clear();
        updatedAt.clear();
        Map<String, AppPrompt> rows = new LinkedHashMap<>();
        String failure = null;
        try {
            for (AppPrompt row : promptRepository.findAll()) {
                rows.put(row.getKey(), row);
            }
        } catch (Exception e) {
            failure = "the app_prompt table could not be read (" + e.getMessage() + ")";
            log.warn("Prompt table unavailable ({}), running on registry defaults: {}",
                    PromptDefaults.CATEGORY, e.getMessage());
        }

        List<String> missing = new ArrayList<>();
        for (PromptDef def : PromptDefaults.all()) {
            AppPrompt row = rows.get(def.getKey());
            if (row != null && row.getContent() != null && !row.getContent().isBlank()) {
                String content = row.getContent();
                if (PromptDefaults.isSuperseded(def.getKey(), content)) {
                    content = repairSuperseded(def);
                }
                cache.put(def.getKey(), content);
                // updated_at is nullable, and ConcurrentHashMap rejects null values —
                // putting a null timestamp here would throw out of reload() during
                // @PostConstruct and abort startup. An absent stamp is fine; the
                // admin UI simply omits the "Updated" line.
                if (row.getUpdatedAt() != null) {
                    updatedAt.put(def.getKey(), row.getUpdatedAt());
                }
            } else {
                if (row == null) {
                    missing.add(def.getKey());
                }
                cache.put(def.getKey(), def.getDefaultText());
            }
        }

        if (failure != null) {
            dbBacked = false;
            unavailableReason = failure
                    + " — run src/main/resources/db/schema.sql, then db/data.sql";
        } else if (!missing.isEmpty()) {
            dbBacked = false;
            unavailableReason = missing.size() + " prompt(s) have no row in app_prompt ("
                    + String.join(", ", missing) + ") — run src/main/resources/db/data.sql";
            log.warn("Prompts not editable: {}", unavailableReason);
        } else {
            dbBacked = true;
            unavailableReason = "";
        }

        log.info("PromptService: {} prompts loaded ({} from DB, {} customized, editable={})",
                cache.size(), rows.size(), countCustomized(), dbBacked);
    }

    /**
     * Replace a stored prompt that still holds a known-bad default with the
     * current one, persisting the repair.
     *
     * <p>Needed because the seed in {@code db/data.sql} is idempotent per key —
     * by design, so admin edits survive — which also means a corrected default
     * never reaches an already-seeded database. Matching on the exact superseded
     * text is what makes this safe: an admin's own wording is not one of these
     * strings, so customisation is never overwritten.
     *
     * <p>Returns the current default even if the write fails, so a locked-down
     * database degrades to correct behaviour rather than the known-bad text.
     */
    private String repairSuperseded(PromptDef def) {
        String current = def.getDefaultText();
        try {
            promptRepository.save(def.getKey(), current);
            log.info("Prompt '{}' held superseded default text — upgraded in DB", def.getKey());
        } catch (Exception e) {
            log.warn("Prompt '{}' held superseded default text and could not be upgraded "
                    + "({}); using the current default in memory only", def.getKey(), e.getMessage());
        }
        return current;
    }

    /**
     * Raw prompt text for the given key. Unknown keys return {@code fallback}
     * (or the empty string) rather than throwing, so a typo cannot break a chat.
     */
    public String get(String key, String fallback) {
        String value = cache.get(key);
        if (value != null) {
            return value;
        }
        String def = PromptDefaults.defaultText(key);
        if (def != null) {
            return def;
        }
        log.warn("Unknown prompt key requested: {}", key);
        return fallback != null ? fallback : "";
    }

    public String get(String key) {
        return get(key, null);
    }

    /**
     * Prompt text with {@code {token}} placeholders replaced. A null or blank
     * value falls back to {@code fallback}. Replacement is single-pass and
     * literal, so a context containing dollar signs is safe.
     */
    public String render(String key, Map<String, String> tokens, String fallback) {
        String template = get(key, fallback);
        if (template == null || template.isEmpty() || tokens == null || tokens.isEmpty()) {
            return template != null ? template : (fallback != null ? fallback : "");
        }
        String result = template;
        for (Map.Entry<String, String> e : tokens.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            result = result.replace("{" + e.getKey() + "}", e.getValue());
        }
        return result;
    }

    public String render(String key, Map<String, String> tokens) {
        return render(key, tokens, null);
    }

    // ---- Admin operations ----

    /**
     * Persist a prompt edit and refresh the cache.
     *
     * <p>Fails loudly when the row is absent: prompts are seeded by
     * {@code db/data.sql}, not by the app, so a missing row means the script has
     * not been run and an edit would silently be lost.
     */
    public AppPrompt save(String key, String content) {
        PromptDef def = requireKnown(key);
        String value = content != null ? content : "";
        AppPrompt existing = null;
        try {
            existing = promptRepository.findByKey(key);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Cannot reach the app_prompt table (" + e.getMessage()
                            + "). Run src/main/resources/db/schema.sql, then db/data.sql.", e);
        }
        if (existing == null) {
            throw new IllegalStateException(
                    "No row in app_prompt for '" + key + "' — the prompts have not been seeded. "
                            + "Run src/main/resources/db/data.sql.");
        }
        AppPrompt saved = promptRepository.save(key, value);
        cache.put(key, value);
        updatedAt.put(key, saved != null && saved.getUpdatedAt() != null
                ? saved.getUpdatedAt() : Instant.now());
        log.info("Prompt updated: {} ({} chars)", key, value.length());
        return toPrompt(def);
    }

    /** Restore the registry default for a prompt. */
    public AppPrompt reset(String key) {
        return save(key, requireKnown(key).getDefaultText());
    }

    /** Restore every prompt to its compiled-in default, in one transaction. */
    public int resetAll() {
        int n = 0;
        for (PromptDef def : PromptDefaults.all()) {
            reset(def.getKey());
            n++;
        }
        log.info("All {} prompts reset to defaults", n);
        return n;
    }

    /** All prompts with registry metadata, in registry order. */
    public List<AppPrompt> listAll() {
        List<AppPrompt> list = new ArrayList<>();
        for (PromptDef def : PromptDefaults.all()) {
            list.add(toPrompt(def));
        }
        return list;
    }

    /** Prompts grouped by group key, preserving registry order. */
    public Map<String, List<AppPrompt>> listGrouped() {
        Map<String, List<AppPrompt>> grouped = new LinkedHashMap<>();
        for (PromptDef def : PromptDefaults.all()) {
            grouped.computeIfAbsent(def.getGroup(), k -> new ArrayList<>()).add(toPrompt(def));
        }
        return grouped;
    }

    /**
     * Prompts as flat, render-ready sections in registry order.
     *
     * <p>Assembled here rather than in the template: resolving a group with
     * {@code th:with} + {@code th:if} silently yields an empty page because
     * Thymeleaf evaluates {@code th:if} first.
     */
    public List<PromptSection> listSections() {
        Map<String, List<AppPrompt>> grouped = listGrouped();
        List<PromptSection> sections = new ArrayList<>();
        for (String group : PromptDefaults.groups()) {
            List<AppPrompt> prompts = grouped.get(group);
            if (prompts == null || prompts.isEmpty()) {
                continue;
            }
            sections.add(new PromptSection(
                    group,
                    PromptDefaults.groupTitle(group),
                    PromptDefaults.groupDescription(group),
                    prompts));
        }
        return sections;
    }

    private AppPrompt toPrompt(PromptDef def) {
        String content = get(def.getKey());
        Instant ts = updatedAt.get(def.getKey());
        return new AppPrompt(
                def.getKey(),
                def.getGroup(),
                PromptDefaults.groupTitle(def.getGroup()),
                def.getLabel(),
                def.getDescription(),
                def.getPlaceholders(),
                content,
                def.getDefaultText(),
                !content.equals(def.getDefaultText()),
                ts);
    }

    private int countCustomized() {
        int n = 0;
        for (PromptDef def : PromptDefaults.all()) {
            if (!def.getDefaultText().equals(get(def.getKey()))) {
                n++;
            }
        }
        return n;
    }

    private PromptDef requireKnown(String key) {
        PromptDef def = PromptDefaults.byKey(key);
        if (def == null) {
            throw new IllegalArgumentException("Unknown prompt key: " + key);
        }
        return def;
    }
}
