package com.example.agentic.llm;

import com.example.agentic.engine.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Minimal Anthropic Messages API client (JDK HttpClient, no SDK). Enabled only when
 * {@code ANTHROPIC_API_KEY} is set and the run is started with {@code --llm}.
 */
public final class AnthropicLlmClient implements LlmClient {

    private static final URI ENDPOINT = URI.create("https://api.anthropic.com/v1/messages");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String apiKey;
    private final String model;

    public AnthropicLlmClient(String apiKey, String model) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("ANTHROPIC_API_KEY is not set");
        }
        this.apiKey = apiKey;
        this.model = model;
    }

    public static LlmClient fromEnv() {
        String key = System.getenv("ANTHROPIC_API_KEY");
        String model = System.getenv().getOrDefault("AGENTIC_LLM_MODEL", "claude-sonnet-5-5");
        return key == null || key.isBlank() ? null : new AnthropicLlmClient(key, model);
    }

    @Override
    public String complete(String system, String user) throws IOException, InterruptedException {
        ObjectNode body = Json.object();
        body.put("model", model);
        body.put("max_tokens", 2000);
        body.put("system", system);
        ObjectNode msg = body.putArray("messages").addObject();
        msg.put("role", "user");
        msg.put("content", user);
        HttpRequest req = HttpRequest.newBuilder(ENDPOINT)
                .timeout(Duration.ofSeconds(60))
                .header("content-type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(Json.compact(body)))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("LLM API returned HTTP " + resp.statusCode());
        }
        JsonNode root = Json.MAPPER.readTree(resp.body());
        StringBuilder text = new StringBuilder();
        for (JsonNode block : root.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                text.append(block.path("text").asText());
            }
        }
        return text.toString();
    }

    @Override
    public String model() {
        return model;
    }
}
