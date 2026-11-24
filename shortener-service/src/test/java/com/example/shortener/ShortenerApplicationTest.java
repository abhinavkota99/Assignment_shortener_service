package com.example.shortener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * End to end over real sockets: full Spring Boot context, embedded server on a random port, journal
 * persistence, Actuator probes, and a restart to prove acknowledged writes survive.
 */
class ShortenerApplicationTest {

    private static final Pattern CODE = Pattern.compile("\"code\"\\s*:\\s*\"([^\"]+)\"");

    @TempDir
    Path tmp;

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
    private ConfigurableApplicationContext app;
    private String base;

    private void start() {
        // Command-line arguments outrank application.yml (builder.properties(...) would only set defaults).
        app = new SpringApplicationBuilder(ShortenerApplication.class)
                .run("--server.port=0",
                        "--shortener.base-url=https://sho.rt",
                        "--shortener.data-file=" + tmp.resolve("links.jsonl"));
        base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port");
    }

    @AfterEach
    void stop() {
        if (app != null) {
            app.close();
        }
    }

    @Test
    void fullLifecycleSurvivesRestart() throws Exception {
        start();
        HttpResponse<String> created = send(HttpRequest.newBuilder(URI.create(base + "/api/v1/links"))
                .header("Content-Type", "application/json").header("X-Request-ID", "req-42")
                .POST(HttpRequest.BodyPublishers.ofString("{\"url\":\"https://example.com/docs?a=1\"}")).build());
        assertEquals(201, created.statusCode(), created.body());
        assertEquals("req-42", created.headers().firstValue("X-Request-ID").orElse(null));
        assertEquals("nosniff", created.headers().firstValue("X-Content-Type-Options").orElse(null));
        assertTrue(created.body().contains("\"shortUrl\":\"https://sho.rt/"), created.body());
        Matcher m = CODE.matcher(created.body());
        assertTrue(m.find());
        String code = m.group(1);

        HttpResponse<String> redirect = send(HttpRequest.newBuilder(URI.create(base + "/" + code))
                .header("Referer", "https://news.example").GET().build());
        assertEquals(302, redirect.statusCode());
        assertEquals("https://example.com/docs?a=1", redirect.headers().firstValue("Location").orElse(null));

        app.close(); // restart: state must be replayed from the journal
        start();

        HttpResponse<String> stats = send(HttpRequest.newBuilder(URI.create(base + "/api/v1/links/" + code + "/stats")).GET().build());
        assertEquals(200, stats.statusCode(), stats.body());
        assertTrue(stats.body().contains("\"totalClicks\":1"), stats.body());
        assertTrue(stats.body().contains("https://news.example"), stats.body());
    }

    @Test
    void actuatorProbesAndMetrics() throws Exception {
        start();
        HttpResponse<String> readiness = send(HttpRequest.newBuilder(URI.create(base + "/actuator/health/readiness")).GET().build());
        assertEquals(200, readiness.statusCode(), readiness.body());
        assertTrue(readiness.body().contains("\"UP\""), readiness.body());
        assertEquals(200, send(HttpRequest.newBuilder(URI.create(base + "/actuator/health/liveness")).GET().build()).statusCode());

        send(HttpRequest.newBuilder(URI.create(base + "/api/v1/links")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"url\":\"https://example.com/\"}")).build());
        HttpResponse<String> metric = send(HttpRequest.newBuilder(URI.create(base + "/actuator/metrics/shortener.links.created")).GET().build());
        assertEquals(200, metric.statusCode(), metric.body());
    }

    @Test
    void errorsUseTheUniformShape() throws Exception {
        start();
        HttpResponse<String> bad = send(HttpRequest.newBuilder(URI.create(base + "/api/v1/links")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"url\":\"http://169.254.169.254/latest\"}")).build());
        assertEquals(400, bad.statusCode());
        assertTrue(bad.body().contains("\"code\":\"validation_failed\""), bad.body());
        HttpResponse<String> missing = send(HttpRequest.newBuilder(URI.create(base + "/nope1234")).GET().build());
        assertEquals(404, missing.statusCode());
        assertTrue(missing.body().contains("\"code\":\"not_found\""), missing.body());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
