package com.example.shortener.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** Binds {@link ShortenerApi} to the JDK's built-in HTTP server with a bounded worker pool. */
public final class HttpServerAdapter implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger("shortener.access");

    private final HttpServer server;
    private final ExecutorService workers;
    private final ShortenerApi api;
    private final int maxBodyBytes;
    private final ObjectMapper json = new ObjectMapper();

    public HttpServerAdapter(ShortenerApi api, String host, int port, int maxBodyBytes, int workerThreads) throws IOException {
        this.api = api;
        this.maxBodyBytes = maxBodyBytes;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 128);
        this.workers = Executors.newFixedThreadPool(workerThreads);
        server.setExecutor(workers);
        server.createContext("/", this::exchange);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void exchange(HttpExchange ex) throws IOException {
        long started = System.nanoTime();
        String requestId = firstNonBlank(ex.getRequestHeaders().getFirst("X-Request-ID"), UUID.randomUUID().toString());
        ApiResponse resp;
        try {
            byte[] body = readBounded(ex.getRequestBody());
            if (body == null) {
                resp = ApiResponse.error(413, "payload_too_large", "body exceeds " + maxBodyBytes + " bytes");
            } else {
                Map<String, String> headers = new HashMap<>();
                ex.getRequestHeaders().forEach((k, v) -> headers.put(k, v.isEmpty() ? "" : v.get(0)));
                resp = api.handle(new ApiRequest(ex.getRequestMethod(), ex.getRequestURI().getRawPath()
                        + (ex.getRequestURI().getRawQuery() != null ? "?" + ex.getRequestURI().getRawQuery() : ""),
                        headers, body, ex.getRemoteAddress().getAddress().getHostAddress()));
            }
            write(ex, resp, requestId);
        } finally {
            ex.close();
        }
        LOG.info(() -> String.format("{\"event\":\"http_request\",\"requestId\":\"%s\",\"method\":\"%s\",\"path\":\"%s\",\"durationMs\":%.2f}",
                requestId, ex.getRequestMethod(), ex.getRequestURI().getRawPath().replace("\"", ""),
                (System.nanoTime() - started) / 1e6));
    }

    /** Reads at most maxBodyBytes; returns null if the body is larger (never buffers unbounded input). */
    private byte[] readBounded(InputStream in) throws IOException {
        byte[] data = in.readNBytes(maxBodyBytes + 1);
        return data.length > maxBodyBytes ? null : data;
    }

    private void write(HttpExchange ex, ApiResponse resp, String requestId) throws IOException {
        byte[] payload = resp.body() == null || ex.getRequestMethod().equals("HEAD")
                ? new byte[0] : json.writeValueAsBytes(resp.body());
        var h = ex.getResponseHeaders();
        h.put("X-Request-ID", List.of(requestId));
        h.put("X-Content-Type-Options", List.of("nosniff"));
        if (resp.body() != null) {
            h.put("Content-Type", List.of("application/json"));
        }
        resp.headers().forEach((k, v) -> h.put(k, List.of(v)));
        ex.sendResponseHeaders(resp.status(), payload.length == 0 ? -1 : payload.length);
        if (payload.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(payload);
            }
        }
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() && a.length() <= 128 ? a : b;
    }

    /** Graceful shutdown: stop accepting, give in-flight requests time to finish. */
    @Override
    public void close() {
        server.stop(2);
        workers.shutdown();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
