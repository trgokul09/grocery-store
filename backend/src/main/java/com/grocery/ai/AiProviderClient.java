package com.grocery.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Provider abstraction for GroceryGPT supporting:
 * 1. Anthropic Claude API (CLAUDE)
 * 2. OpenAI Chat Completions API (OPENAI)
 * 3. Google Gemini API (GEMINI)
 * 4. Grounded Local Business Engine (when no external API key is configured or if external call fails)
 *
 * API keys are strictly stored on the backend via environment variables and never exposed to the frontend.
 */
@Component
public class AiProviderClient {

    private static final Logger log = LoggerFactory.getLogger(AiProviderClient.class);

    public record SynthesisResult(String text, String providerUsed) {}

    @Value("${app.ai.provider:AUTO}")
    private String configuredProvider;

    @Value("${app.ai.openai-api-key:}")
    private String openAiApiKey;

    @Value("${app.ai.openai-model:gpt-4o-mini}")
    private String openAiModel;

    @Value("${app.ai.claude-api-key:}")
    private String claudeApiKey;

    @Value("${app.ai.claude-model:claude-3-5-sonnet-latest}")
    private String claudeModel;

    @Value("${app.ai.gemini-api-key:}")
    private String geminiApiKey;

    @Value("${app.ai.gemini-model:gemini-1.5-flash}")
    private String geminiModel;

    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public AiProviderClient(ObjectMapper mapper) {
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    }

    public String getActiveProviderLabel() {
        String p = configuredProvider == null ? "AUTO" : configuredProvider.trim().toUpperCase(Locale.ROOT);
        if (("CLAUDE".equals(p) || "AUTO".equals(p)) && hasKey(claudeApiKey)) {
            return "CLAUDE (" + claudeModel + ")";
        }
        if (("OPENAI".equals(p) || "AUTO".equals(p)) && hasKey(openAiApiKey)) {
            return "OPENAI (" + openAiModel + ")";
        }
        if (("GEMINI".equals(p) || "AUTO".equals(p)) && hasKey(geminiApiKey)) {
            return "GEMINI (" + geminiModel + ")";
        }
        return "GROCERYGPT_GROUNDED_ENGINE";
    }

    public SynthesisResult synthesize(
        String userRole,
        String userFullName,
        String userQuestion,
        String groundedContextMarkdown,
        List<AiMessage> recentHistory
    ) {
        String systemPrompt = buildSystemPrompt(userRole, userFullName, groundedContextMarkdown);
        String p = configuredProvider == null ? "AUTO" : configuredProvider.trim().toUpperCase(Locale.ROOT);

        try {
            if (("CLAUDE".equals(p) || "AUTO".equals(p)) && hasKey(claudeApiKey)) {
                String out = callClaude(systemPrompt, userQuestion, recentHistory);
                if (out != null && !out.isBlank()) {
                    return new SynthesisResult(out, "CLAUDE");
                }
            }
            if (("OPENAI".equals(p) || "AUTO".equals(p)) && hasKey(openAiApiKey)) {
                String out = callOpenAi(systemPrompt, userQuestion, recentHistory);
                if (out != null && !out.isBlank()) {
                    return new SynthesisResult(out, "OPENAI");
                }
            }
            if (("GEMINI".equals(p) || "AUTO".equals(p)) && hasKey(geminiApiKey)) {
                String out = callGemini(systemPrompt, userQuestion);
                if (out != null && !out.isBlank()) {
                    return new SynthesisResult(out, "GEMINI");
                }
            }
        } catch (Exception e) {
            log.warn("External AI provider call failed ({}); falling back to Grounded Business Engine", e.getMessage());
        }

        return new SynthesisResult(groundedContextMarkdown, "GROCERYGPT_GROUNDED_ENGINE");
    }

    private boolean hasKey(String key) {
        return key != null && !key.isBlank() && !key.startsWith("your-");
    }

    private String buildSystemPrompt(String userRole, String userFullName, String groundedContextMarkdown) {
        return """
            You are GroceryGPT, an experienced, friendly Indian grocery retail & accounting business consultant for %s (Role: %s).

            CAPABILITIES & RULES:
            1. Talk naturally like a knowledgeable business advisor — warm, practical, and easy to understand for a shop owner.
            2. **Real-Time Store Data:** Whenever you state store-specific numbers (sales, profits, stock levels, expenses, salaries, customers), use ONLY the verified facts inside <GROUNDED_CONTEXT>. NEVER invent or guess store figures.
            3. **General Business Knowledge & Consulting:** For strategy, marketing, customer retention, festival offers, summer stocking, cash flow, or retail concepts not stored in the database, freely apply your grocery business expertise tailored to Indian retail — and clearly distinguish general recommendations from actual store records.
            4. **RBAC & Missing Data:** If <GROUNDED_CONTEXT> indicates a role restriction or missing data, respect it politely and still give helpful general guidance where appropriate.
            5. Format currency in Indian Rupees (₹), keep answers crisp and actionable, and use everyday Indian grocery examples.

            <GROUNDED_CONTEXT>
            %s
            </GROUNDED_CONTEXT>
            """.formatted(userFullName, userRole, groundedContextMarkdown);
    }

    private String callOpenAi(String systemPrompt, String userQuestion, List<AiMessage> recentHistory) throws Exception {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        for (AiMessage m : recentHistory) {
            messages.add(Map.of(
                "role", "USER".equalsIgnoreCase(m.role) ? "user" : "assistant",
                "content", m.content
            ));
        }
        messages.add(Map.of("role", "user", "content", userQuestion));

        Map<String, Object> payload = Map.of(
            "model", openAiModel,
            "temperature", 0.1,
            "messages", messages
        );

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("https://api.openai.com/v1/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + openAiApiKey.trim())
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
            .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
            JsonNode root = mapper.readTree(resp.body());
            return root.path("choices").path(0).path("message").path("content").asText(null);
        }
        log.warn("OpenAI API returned HTTP {}", resp.statusCode());
        return null;
    }

    private String callClaude(String systemPrompt, String userQuestion, List<AiMessage> recentHistory) throws Exception {
        List<Map<String, String>> messages = new ArrayList<>();
        for (AiMessage m : recentHistory) {
            messages.add(Map.of(
                "role", "USER".equalsIgnoreCase(m.role) ? "user" : "assistant",
                "content", m.content
            ));
        }
        messages.add(Map.of("role", "user", "content", userQuestion));

        Map<String, Object> payload = Map.of(
            "model", claudeModel,
            "max_tokens", 1024,
            "temperature", 0.1,
            "system", systemPrompt,
            "messages", messages
        );

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("https://api.anthropic.com/v1/messages"))
            .header("Content-Type", "application/json")
            .header("x-api-key", claudeApiKey.trim())
            .header("anthropic-version", "2023-06-01")
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
            .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
            JsonNode root = mapper.readTree(resp.body());
            return root.path("content").path(0).path("text").asText(null);
        }
        log.warn("Claude API returned HTTP {}", resp.statusCode());
        return null;
    }

    private String callGemini(String systemPrompt, String userQuestion) throws Exception {
        Map<String, Object> payload = Map.of(
            "system_instruction", Map.of("parts", List.of(Map.of("text", systemPrompt))),
            "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", userQuestion)))),
            "generationConfig", Map.of("temperature", 0.1)
        );
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + geminiModel + ":generateContent?key=" + geminiApiKey.trim();
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
            .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
            JsonNode root = mapper.readTree(resp.body());
            return root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText(null);
        }
        log.warn("Gemini API returned HTTP {}", resp.statusCode());
        return null;
    }
}
