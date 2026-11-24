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

    private static final URI DEFAULT_ENDPOINT = URI.create("https://api.anthropic.com/v1/messages");
    private static final int MAX_DETAIL_CHARS = 300;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String apiKey;
    private final String model;
    private final URI endpoint;

    public AnthropicLlmClient(String apiKey, String model) {
        this(apiKey, model, DEFAULT_ENDPOINT);
    }

    AnthropicLlmClient(String apiKey, String model, URI endpoint) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("ANTHROPIC_API_KEY is not set");
        }
        this.apiKey = apiKey;
        this.model = model;
        this.endpoint = endpoint;
    }

    public static LlmClient fromEnv() {
        String key = System.getenv("ANTHROPIC_API_KEY");
        String model = System.getenv().getOrDefault("AGENTIC_LLM_MODEL", "claude-sonnet-5-5");
        return key == null || key.isBlank() ? null : new AnthropicLlmClient(key, model);
    }

    @Override
    public String complete(String system, String user) throws IOException, InterruptedException, RejectedException {
        return complete(system, user, 2000);
    }

    @Override
    public String complete(String system, String user, int maxTokens)
            throws IOException, InterruptedException, RejectedException {
        ObjectNode body = Json.object();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("system", system);
        ObjectNode msg = body.putArray("messages").addObject();
        msg.put("role", "user");
        msg.put("content", user);
        HttpRequest req = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(maxTokens > 4000 ? 300 : 60))
                .header("content-type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(Json.compact(body)))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        int status = resp.statusCode();
        if (status != 200) {
            String detail = errorDetail(resp.body());
            String err = "LLM API returned HTTP " + status + (detail.isEmpty() ? "" : ": " + detail);
            if (status == 408 || status == 429 || status >= 500) {
                throw new IOException(err); // timeout, rate limit, overload: worth retrying with backoff
            }
            throw new RejectedException(err); // e.g. 400 no credit / bad model, 401 bad key: retrying cannot help
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

    /** The API's own error message (never contains the key), shortened to one line. */
    static String errorDetail(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String m;
        try {
            m = Json.MAPPER.readTree(body).path("error").path("message").asText("");
        } catch (IOException e) {
            m = "";
        }
        m = m.replaceAll("\\s+", " ").trim();
        return m.length() > MAX_DETAIL_CHARS ? m.substring(0, MAX_DETAIL_CHARS) + "..." : m;
    }

    @Override
    public String model() {
        return model;
    }
}
