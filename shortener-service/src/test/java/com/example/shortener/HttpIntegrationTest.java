package com.example.shortener;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.example.shortener.api.HttpServerAdapter;
import com.example.shortener.config.Settings;
import com.example.shortener.store.JournalLinkRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** End-to-end over real sockets: JDK HTTP server + journal persistence + JDK HTTP client. */
public class HttpIntegrationTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
    private Path dataFile;
    private JournalLinkRepository repo;
    private HttpServerAdapter server;
    private String base;

    @Before
    public void start() throws Exception {
        dataFile = tmp.getRoot().toPath().resolve("links.jsonl");
        startServer();
    }

    private void startServer() throws Exception {
        Settings settings = new Settings("127.0.0.1", 0, "https://sho.rt", dataFile.toString(), 7, 5, 2048, 1024, 50, 50, false);
        repo = new JournalLinkRepository(dataFile);
        server = ShortenerApplication.start(settings, repo);
        base = "http://127.0.0.1:" + server.port();
    }

    @After
    public void stop() {
        server.close();
        repo.close();
    }

    @Test
    public void fullLifecycleSurvivesRestart() throws Exception {
        HttpResponse<String> created = send(HttpRequest.newBuilder(URI.create(base + "/api/v1/links"))
                .header("Content-Type", "application/json").header("X-Request-ID", "req-42")
                .POST(HttpRequest.BodyPublishers.ofString("{\"url\":\"https://example.com/docs?a=1\"}")).build());
        assertEquals(201, created.statusCode());
        assertEquals("req-42", created.headers().firstValue("X-Request-ID").orElse(null));
        assertEquals("nosniff", created.headers().firstValue("X-Content-Type-Options").orElse(null));
        JsonNode body = json.readTree(created.body());
        String code = body.get("code").asText();
        assertTrue(body.get("shortUrl").asText().startsWith("https://sho.rt/"));

        HttpResponse<String> redirect = send(HttpRequest.newBuilder(URI.create(base + "/" + code)).header("Referer", "https://news.example").GET().build());
        assertEquals(302, redirect.statusCode());
        assertEquals("https://example.com/docs?a=1", redirect.headers().firstValue("Location").orElse(null));

        // restart: data must be replayed from the journal
        stop();
        startServer();

        HttpResponse<String> stats = send(HttpRequest.newBuilder(URI.create(base + "/api/v1/links/" + code + "/stats")).GET().build());
        assertEquals(200, stats.statusCode());
        JsonNode s = json.readTree(stats.body());
        assertEquals(1, s.get("totalClicks").asInt());
        assertEquals("https://news.example", s.get("topReferrers").get(0).get("key").asText());
        assertNotNull(s.get("lastClickAt").asText());
    }

    @Test
    public void oversizedBodyIsRejected() throws Exception {
        HttpResponse<String> resp = send(HttpRequest.newBuilder(URI.create(base + "/api/v1/links"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"url\":\"https://e.com/" + "a".repeat(5000) + "\"}")).build());
        assertEquals(413, resp.statusCode());
    }

    @Test
    public void readinessReportsReady() throws Exception {
        assertEquals(200, send(HttpRequest.newBuilder(URI.create(base + "/readyz")).GET().build()).statusCode());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
