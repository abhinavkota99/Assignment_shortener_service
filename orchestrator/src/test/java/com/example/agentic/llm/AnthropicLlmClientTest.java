package com.example.agentic.llm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** HTTP behaviour of the client against a local fake of the Messages API. */
public class AnthropicLlmClientTest {

    private HttpServer server;
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<String> body = new AtomicReference<>("");
    private final AtomicReference<String> seenKey = new AtomicReference<>();

    @Before
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", ex -> {
            seenKey.set(ex.getRequestHeaders().getFirst("x-api-key"));
            byte[] b = body.get().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status.get(), b.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(b);
            }
        });
        server.start();
    }

    @After
    public void stop() {
        server.stop(0);
    }

    private AnthropicLlmClient client() {
        return new AnthropicLlmClient("test-key-123", "m", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages"));
    }

    @Test
    public void returnsTheTextBlocks() throws Exception {
        body.set("{\"content\":[{\"type\":\"text\",\"text\":\"hello \"},{\"type\":\"text\",\"text\":\"world\"}]}");
        assertEquals("hello world", client().complete("s", "u"));
        assertEquals("test-key-123", seenKey.get());
    }

    @Test
    public void rejectedRequestIsNotRetryableAndExplainsWhy() throws Exception {
        status.set(400);
        body.set("{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"Your credit balance is too low.\"}}");
        try {
            client().complete("s", "u");
            fail();
        } catch (LlmClient.RejectedException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("HTTP 400: Your credit balance is too low."));
            assertFalse("never echoes the key", e.getMessage().contains("test-key-123"));
        }
    }

    @Test
    public void overloadAndRateLimitsAreRetryable() throws Exception {
        for (int s : new int[] {429, 500, 529}) {
            status.set(s);
            body.set("not json");
            try {
                client().complete("s", "u");
                fail("HTTP " + s);
            } catch (IOException e) {
                assertEquals("LLM API returned HTTP " + s, e.getMessage());
            }
        }
    }
}
