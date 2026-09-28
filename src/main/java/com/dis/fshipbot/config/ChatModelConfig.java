package com.dis.fshipbot.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class ChatModelConfig {

    // ---- Gemini ----

    @Value("${gemini.api-key:}")
    private String geminiKey;

    @Value("${gemini.model:gemini-2.5-flash-lite}")
    private String geminiModel;

    @Value("${gemini.temperature:0.2}")
    private Double geminiTemp;

    @Value("${gemini.max-tokens:2048}")
    private Integer geminiMaxTokens;

    @Value("${gemini.top-p:0.95}")
    private Double geminiTopP;

    // ---- OpenAI ----

    @Value("${openai.api-key:}")
    private String openaiKey;

    @Value("${openai.model:gpt-4o-mini}")
    private String openaiModel;

    @Value("${openai.temperature:0.2}")
    private Double openaiTemp;

    @Value("${openai.max-tokens:2048}")
    private Integer openaiMaxTokens;

    @Value("${openai.timeout-seconds:60}")
    private Integer openaiTimeoutSeconds;

    // ---- NVIDIA NIM ----

    @Value("${nim.api-key:}")
    private String nimKey;

    @Value("${nim.model:nvidia/nemotron-3-super-120b-a12b}")
    private String nimModel;

    @Value("${nim.temperature:0.2}")
    private Double nimTemp;

    @Value("${nim.max-tokens:4096}")
    private Integer nimMaxTokens;

    @Value("${nim.timeout-seconds:60}")
    private Integer nimTimeoutSeconds;

    // ---- Beans ----

    @Bean("gemini")
    public ChatLanguageModel geminiChatModel() {
        String key = geminiKey;
        if (key == null || key.isBlank()) {
            log.warn("GEMINI_API_KEY not set. Gemini will not work until key is provided.");
            key = "DUMMY-KEY-FOR-STARTUP";
        }
        log.info("Chat model: Gemini / {}", geminiModel);
        return GoogleAiGeminiChatModel.builder()
                .apiKey(key)
                .modelName(geminiModel)
                .temperature(geminiTemp)
                .maxOutputTokens(geminiMaxTokens)
                .topP(geminiTopP)
                .build();
    }

    @Bean("openai")
    public ChatLanguageModel openAiChatModel() {
        String key = openaiKey;
        if (key == null || key.isBlank()) {
            log.warn("OPENAI_API_KEY not set. OpenAI will not work until key is provided.");
            key = "DUMMY-KEY-FOR-STARTUP";
        }
        log.info("Chat model: OpenAI / {}", openaiModel);
        return OpenAiChatModel.builder()
                .apiKey(key)
                .modelName(openaiModel)
                .temperature(openaiTemp)
                .maxTokens(openaiMaxTokens)
                // Explicit timeout (was driver default 60s) + single internal retry:
                // ChatService.generateWithRetry owns the retry policy (quota fail-fast
                // vs transient backoff), so stacked LangChain4j retries must not
                // multiply wall time behind its back.
                .timeout(java.time.Duration.ofSeconds(openaiTimeoutSeconds))
                .maxRetries(1)
                .build();
    }

    @Bean("nim")
    public ChatLanguageModel nimChatModel() {
        String key = nimKey;
        if (key == null || key.isBlank()) {
            log.warn("NVIDIA_API_KEY not set. NIM will not work until key is provided.");
            key = "DUMMY-KEY-FOR-STARTUP";
        }
        log.info("Chat model: NVIDIA NIM / {}", nimModel);
        return OpenAiChatModel.builder()
                .apiKey(key)
                .baseUrl("https://integrate.api.nvidia.com/v1")
                .modelName(nimModel)
                .temperature(nimTemp)
                .maxTokens(nimMaxTokens)
                // Explicit timeout (was driver default 60s — the 2026-09-27 log shows
                // the first attempt dying on it) + single internal retry for the
                // same stacked-retry reason as the OpenAI bean above.
                .timeout(java.time.Duration.ofSeconds(nimTimeoutSeconds))
                .maxRetries(1)
                .build();
    }
}
