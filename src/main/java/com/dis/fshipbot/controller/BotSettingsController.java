package com.dis.fshipbot.controller;

import com.dis.fshipbot.config.PageAccessInterceptor;
import com.dis.fshipbot.model.AppPrompt;
import com.dis.fshipbot.model.AppSetting;
import com.dis.fshipbot.model.AppSuggestion;
import com.dis.fshipbot.service.AppConfigService;
import com.dis.fshipbot.service.PromptService;
import com.dis.fshipbot.service.ProviderService;
import com.dis.fshipbot.util.PromptDefaults;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Admin pages and their JSON APIs.
 *
 * <p>Page access is gated by {@link PageAccessInterceptor} (date key in session).
 * The API methods under {@code /api/**} are covered by the same interceptor
 * patterns — see {@code WebMvcConfig} — so no endpoint here is reachable without
 * a valid session.
 */
@Controller
@RequestMapping("/admin")
public class BotSettingsController {

    @Autowired
    private AppConfigService configService;

    @Autowired
    private ProviderService providerService;

    @Autowired
    private PromptService promptService;

    // ---- Pages ----

    /** Entry point: the admin panel shell with its side menu. */
    @GetMapping({"", "/", "/index"})
    public String adminHome() {
        return "redirect:/admin/prompts";
    }

    /** Prompts page — edit every LLM prompt by group. */
    @GetMapping("/prompts")
    public String promptsPage(Model model) {
        model.addAttribute("activeMenu", "prompts");
        model.addAttribute("promptSections", promptService.listSections());
        model.addAttribute("promptCount", PromptDefaults.all().size());
        model.addAttribute("promptsDbBacked", promptService.isDbBacked());
        model.addAttribute("promptsUnavailableReason", promptService.getUnavailableReason());
        return "admin-prompts";
    }

    /** App settings page — key/value settings + suggested questions. */
    @GetMapping("/settings")
    public String settingsPage(Model model) {
        model.addAttribute("activeMenu", "settings");
        model.addAttribute("settings", visibleSettings());
        model.addAttribute("suggestions", configService.getAllSuggestions());
        return "admin-settings";
    }

    /**
     * Settings shown in the admin table: everything except the provider (managed
     * on the Document Tools page) and prompts (their own page).
     */
    private static final Set<String> ALLOWED_CATEGORIES = Set.of("page", "bot", "welcome", "footer");

    private List<AppSetting> visibleSettings() {
        return configService.getAllSettings().stream()
                .filter(s -> ALLOWED_CATEGORIES.contains(s.getCategory()))
                .collect(Collectors.toList());
    }

    // ---- Settings API ----

    @GetMapping("/api/settings")
    @ResponseBody
    public Map<String, Object> getSettings() {
        return Map.of("settings", visibleSettings());
    }

    @PostMapping("/api/settings")
    @ResponseBody
    public ResponseEntity<?> saveSetting(@RequestBody AppSetting setting) {
        try {
            AppSetting saved = configService.updateSetting(
                    setting.getConfigKey(), setting.getConfigValue(), setting.getCategory());
            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/api/settings/{key}")
    @ResponseBody
    public ResponseEntity<?> deleteSetting(@PathVariable String key) {
        configService.deleteSetting(key);
        providerService.invalidateCache(key);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    // ---- Prompts API ----

    /** All editable prompts, grouped, with registry metadata and default text. */
    @GetMapping("/api/prompts")
    @ResponseBody
    public Map<String, Object> getPrompts() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sections", promptService.listSections());
        out.put("prompts", promptService.listAll());
        return out;
    }

    @PostMapping("/api/prompts")
    @ResponseBody
    public ResponseEntity<?> savePrompt(@RequestBody AppPrompt prompt) {
        if (prompt == null || prompt.getKey() == null || prompt.getKey().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'key' field"));
        }
        try {
            return ResponseEntity.ok(promptService.save(prompt.getKey(), prompt.getContent()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/api/prompts/reset")
    @ResponseBody
    public ResponseEntity<?> resetPrompt(@RequestBody Map<String, String> body) {
        String key = body.get("key");
        if (key == null || key.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'key' field"));
        }
        try {
            return ResponseEntity.ok(promptService.reset(key));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Restore every prompt to the compiled default in one call. */
    @PostMapping("/api/prompts/reset-all")
    @ResponseBody
    public ResponseEntity<?> resetAllPrompts() {
        try {
            int n = promptService.resetAll();
            return ResponseEntity.ok(Map.of("status", "reset", "count", n));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Re-read every prompt from the DB without a restart.
     *
     * <p>Reachable from the Prompts page's "Check again" button so an admin can
     * run the SQL scripts and have the page pick the edits up without a
     * redeploy. This is a read, not a write — the app still never seeds prompts.
     */
    @PostMapping("/api/prompts/reload")
    @ResponseBody
    public Map<String, Object> reloadPrompts() {
        promptService.reload();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "reloaded");
        out.put("prompts", promptService.listAll());
        out.put("dbBacked", promptService.isDbBacked());
        out.put("unavailableReason", promptService.getUnavailableReason());
        return out;
    }

    // ---- Suggestions API ----

    @GetMapping("/api/suggestions")
    @ResponseBody
    public Map<String, Object> getSuggestions() {
        return Map.of("suggestions", configService.getAllSuggestions());
    }

    @PostMapping("/api/suggestions")
    @ResponseBody
    public ResponseEntity<?> addSuggestion(@RequestBody AppSuggestion suggestion) {
        try {
            AppSuggestion saved = configService.addSuggestion(suggestion);
            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/api/suggestions/{id}")
    @ResponseBody
    public ResponseEntity<?> updateSuggestion(@PathVariable Long id, @RequestBody AppSuggestion suggestion) {
        try {
            AppSuggestion saved = configService.updateSuggestion(id, suggestion);
            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/api/suggestions/{id}/toggle")
    @ResponseBody
    public ResponseEntity<?> toggleSuggestion(@PathVariable Long id) {
        configService.toggleSuggestion(id);
        return ResponseEntity.ok(Map.of("status", "toggled"));
    }

    @PutMapping("/api/suggestions/{id}/reorder")
    @ResponseBody
    public ResponseEntity<?> reorderSuggestion(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        int direction = body.getOrDefault("direction", 0);
        configService.reorderSuggestion(id, direction);
        return ResponseEntity.ok(Map.of("status", "reordered"));
    }

    @DeleteMapping("/api/suggestions/{id}")
    @ResponseBody
    public ResponseEntity<?> deleteSuggestion(@PathVariable Long id) {
        configService.deleteSuggestion(id);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    // ---- Session ----

    /** Ends the admin session and returns to the login screen. */
    @PostMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/admin/login";
    }

    // ---- XML API ----

    @GetMapping(value = "/api/config.xml", produces = MediaType.APPLICATION_XML_VALUE)
    @ResponseBody
    public String getConfigXml() {
        return configService.buildXml();
    }

    // ---- Provider API ----

    @GetMapping("/api/provider")
    @ResponseBody
    public Map<String, Object> getProvider() {
        return providerService.getProviderInfo();
    }

    @PostMapping("/api/provider")
    @ResponseBody
    public ResponseEntity<?> setProvider(@RequestBody Map<String, String> body) {
        try {
            String provider = body.get("provider");
            if (provider == null || provider.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Missing 'provider' field"));
            }
            providerService.setProvider(provider);
            return ResponseEntity.ok(providerService.getProviderInfo());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/api/provider/model")
    @ResponseBody
    public ResponseEntity<?> setModel(@RequestBody Map<String, String> body) {
        try {
            String provider = body.get("provider");
            String model = body.get("model");
            if (provider == null || provider.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Missing 'provider' field"));
            }
            if (model == null || model.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Missing 'model' field"));
            }
            providerService.setModel(provider, model);
            return ResponseEntity.ok(providerService.getProviderInfo());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
