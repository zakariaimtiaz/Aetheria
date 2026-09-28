package com.dis.fshipbot.service;

import com.dis.fshipbot.repository.AppSettingRepository;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ProviderService {

    private static final String PROVIDER_KEY = "chat.provider";
    private static final String CATEGORY = "provider";

    @Autowired
    private AppSettingRepository settingRepository;

    @Autowired
    @Qualifier("gemini")
    private ChatLanguageModel geminiModel;

    @Autowired
    @Qualifier("openai")
    private ChatLanguageModel openaiModel;

    @Autowired
    @Qualifier("nim")
    private ChatLanguageModel nimModel;

    @Value("${chat.provider:gemini}")
    private String defaultProvider;

    @Value("${gemini.api-key:}")
    private String geminiKey;

    @Value("${openai.api-key:}")
    private String openaiKey;

    @Value("${nim.api-key:}")
    private String nimKey;

    @Value("${gemini.model:gemini-2.5-flash-lite}")
    private String geminiDefaultModel;

    @Value("${openai.model:gpt-4o-mini}")
    private String openaiDefaultModel;

    @Value("${nim.model:nvidia/nemotron-3-super-120b-a12b}")
    private String nimDefaultModel;

    private String currentProvider;
    private final Map<String, String> modelCache = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        String saved = settingRepository.getValue(PROVIDER_KEY, null);
        currentProvider = (saved != null && !saved.isBlank()) ? saved : defaultProvider;

        // Pre-load model overrides from DB
        for (String p : List.of("gemini", "openai", "nim")) {
            String dbModel = settingRepository.getValue(p + ".model", null);
            if (dbModel != null && !dbModel.isBlank()) {
                modelCache.put(p, dbModel);
            }
        }

        log.info("ProviderService: active provider = {}", currentProvider);
        log.info("   Gemini model: {}", getModelName("gemini"));
        log.info("   OpenAI model: {}", getModelName("openai"));
        log.info("   NIM model: {}", getModelName("nim"));
    }

    public String getCurrentProvider() {
        return currentProvider;
    }

    public ChatLanguageModel getCurrentModel() {
        String provider = currentProvider.toLowerCase();
        if ("openai".equals(provider)) {
            return openaiModel;
        } else if ("nim".equals(provider)) {
            return nimModel;
        } else {
            return geminiModel;
        }
    }

    public String getCurrentModelName() {
        return getModelName(currentProvider);
    }

    public String getModelName(String provider) {
        String cached = modelCache.get(provider.toLowerCase());
        if (cached != null) return cached;
        String normalized = provider.toLowerCase();
        if ("openai".equals(normalized)) {
            return openaiDefaultModel;
        } else if ("nim".equals(normalized)) {
            return nimDefaultModel;
        } else {
            return geminiDefaultModel;
        }
    }

    public void setModel(String provider, String modelName) {
        String normalized = provider.toLowerCase().trim();
        String key = normalized + ".model";
        settingRepository.upsert(key, modelName, CATEGORY);
        modelCache.put(normalized, modelName);

        // Ensure chat.provider is also saved in DB
        String savedProvider = settingRepository.getValue(PROVIDER_KEY, null);
        if (savedProvider == null || savedProvider.isBlank()) {
            settingRepository.upsert(PROVIDER_KEY, normalized, CATEGORY);
            this.currentProvider = normalized;
            log.info("Provider auto-saved to DB: {}", normalized);
        }

        log.info("Model updated for {}: {}", normalized, modelName);
    }

    public void invalidateCache(String key) {
        if (PROVIDER_KEY.equals(key)) {
            String fromDb = settingRepository.getValue(PROVIDER_KEY, null);
            this.currentProvider = (fromDb != null && !fromDb.isBlank()) ? fromDb : defaultProvider;
            log.info("Provider cache invalidated, now: {}", currentProvider);
        } else if (key != null && key.endsWith(".model")) {
            String provider = key.replace(".model", "");
            String fromDb = settingRepository.getValue(key, null);
            if (fromDb != null && !fromDb.isBlank()) {
                modelCache.put(provider, fromDb);
            } else {
                modelCache.remove(provider);
            }
            log.info("Model cache invalidated for {}: {}", provider, fromDb);
        }
    }

    public void setProvider(String provider) {
        String normalized = provider.toLowerCase().trim();
        if (!normalized.equals("gemini") && !normalized.equals("openai") && !normalized.equals("nim")) {
            throw new IllegalArgumentException("Invalid provider: " + provider + ". Must be gemini, openai, or nim.");
        }
        this.currentProvider = normalized;
        settingRepository.upsert(PROVIDER_KEY, normalized, CATEGORY);
        log.info("Provider switched to: {}", normalized);
    }

    public Map<String, Object> getProviderInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("current", currentProvider);
        info.put("currentModel", getCurrentModelName());

        Map<String, Object> available = new LinkedHashMap<>();
        available.put("gemini", Map.of(
                "name", "Gemini",
                "defaultModel", geminiDefaultModel,
                "model", getModelName("gemini"),
                "available", geminiKey != null && !geminiKey.isBlank() && !geminiKey.equals("DUMMY-KEY-FOR-STARTUP")
        ));
        available.put("openai", Map.of(
                "name", "OpenAI",
                "defaultModel", openaiDefaultModel,
                "model", getModelName("openai"),
                "available", openaiKey != null && !openaiKey.isBlank() && !openaiKey.equals("DUMMY-KEY-FOR-STARTUP")
        ));
        available.put("nim", Map.of(
                "name", "NVIDIA NIM",
                "defaultModel", nimDefaultModel,
                "model", getModelName("nim"),
                "available", nimKey != null && !nimKey.isBlank() && !nimKey.equals("DUMMY-KEY-FOR-STARTUP")
        ));
        info.put("providers", available);

        return info;
    }
}
