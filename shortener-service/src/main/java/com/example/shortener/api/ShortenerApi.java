package com.example.shortener.api;

import com.example.shortener.core.Link;
import com.example.shortener.core.ServiceException;
import com.example.shortener.core.ShortenerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP routing and request/response mapping, as a pure function {@link #handle(ApiRequest)} so it is
 * unit-testable without sockets. See docs/openapi.yaml for the contract.
 *
 * <pre>
 *   POST  /api/v1/links               create a short link                201, 200 (idempotent replay), 400, 409, 429
 *   GET   /api/v1/links/{code}        link metadata                      200, 404
 *   GET   /api/v1/links/{code}/stats  click analytics (?days=1..365)     200, 400, 404
 *   GET   /{code}                     redirect and record a click        302, 404
 *   HEAD  /{code}                     redirect, no click recorded        302, 404
 *   GET   /healthz                    liveness                           200
 *   GET   /readyz                     readiness (storage healthy)        200, 503
 *   GET   /metrics                    request counters                   200
 * </pre>
 */
public class ShortenerApi {

    private static final Logger LOG = Logger.getLogger(ShortenerApi.class.getName());
    private static final String CODE = "[A-Za-z0-9][A-Za-z0-9_-]{2,63}";
    private static final Pattern LINK = Pattern.compile("^/api/v1/links/(" + CODE + ")$");
    private static final Pattern STATS = Pattern.compile("^/api/v1/links/(" + CODE + ")/stats$");
    private static final Pattern REDIRECT = Pattern.compile("^/(" + CODE + ")$");
    private static final Set<String> CREATE_FIELDS = Set.of("url", "alias");

    private final ShortenerService service;
    private final TokenBucketRateLimiter limiter;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();

    public ShortenerApi(ShortenerService service, TokenBucketRateLimiter limiter) {
        this.service = service;
        this.limiter = limiter;
    }

    public ApiResponse handle(ApiRequest req) {
        ApiResponse resp;
        try {
            resp = route(req);
        } catch (ServiceException e) {
            resp = ApiResponse.error(e.kind().httpStatus(), e.kind().code(), e.getMessage());
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "unhandled error", e); // never leak internals to clients
            resp = ApiResponse.error(500, "internal_error", "internal server error");
        }
        count("http_requests_total");
        count("http_" + (resp.status() / 100) + "xx");
        return resp;
    }

    public Map<String, Long> metrics() {
        Map<String, Long> out = new TreeMap<>();
        counters.forEach((k, v) -> out.put(k, v.sum()));
        return out;
    }

    private ApiResponse route(ApiRequest req) {
        URI uri;
        try {
            uri = URI.create(req.rawPath().startsWith("/") ? req.rawPath() : "/" + req.rawPath());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, "bad_request", "malformed request path");
        }
        String path = uri.getPath();
        String method = req.method().toUpperCase(Locale.ROOT);

        switch (path) {
            case "/healthz":
                return onlyGet(method, () -> ApiResponse.json(200, Map.of("status", "ok")));
            case "/readyz":
                return onlyGet(method, () -> service.healthy()
                        ? ApiResponse.json(200, Map.of("status", "ready"))
                        : ApiResponse.json(503, Map.of("status", "not_ready")));
            case "/metrics":
                return onlyGet(method, () -> ApiResponse.json(200, metrics()));
            case "/api/v1/links":
                if (!method.equals("POST")) {
                    return methodNotAllowed("POST");
                }
                TokenBucketRateLimiter.Decision d = limiter.tryAcquire(req.clientId());
                if (!d.allowed()) {
                    count("rate_limited_total");
                    return ApiResponse.error(429, "rate_limited", "too many requests",
                            Map.of("Retry-After", Long.toString(d.retryAfterSeconds())));
                }
                return create(req);
            default:
                break;
        }

        Matcher m = STATS.matcher(path);
        if (m.matches()) {
            return onlyGet(method, () -> ApiResponse.json(200, service.stats(m.group(1), intParam(uri, "days", 30))));
        }
        Matcher l = LINK.matcher(path);
        if (l.matches()) {
            return onlyGet(method, () -> ApiResponse.json(200, linkBody(service.get(l.group(1)))));
        }
        Matcher r = REDIRECT.matcher(path);
        if (r.matches()) {
            if (!method.equals("GET") && !method.equals("HEAD")) {
                return methodNotAllowed("GET, HEAD");
            }
            // HEAD (link unfurlers, crawlers) resolves but is not counted as a click.
            Link link = service.resolve(r.group(1), req.header("referer"), req.header("user-agent"), method.equals("GET"));
            count("redirects_total");
            // 302 rather than 301: browsers must not cache the redirect, so every click is counted.
            return new ApiResponse(302, null, Map.of("Location", link.targetUrl(), "Cache-Control", "private, max-age=0"));
        }
        return ApiResponse.error(404, "not_found", "route not found");
    }

    private ApiResponse create(ApiRequest req) {
        String contentType = req.header("content-type");
        if (contentType == null || !contentType.split(";")[0].trim().equalsIgnoreCase("application/json")) {
            return ApiResponse.error(415, "unsupported_media_type", "Content-Type must be application/json");
        }
        JsonNode body;
        try {
            body = json.readTree(req.body());
        } catch (IOException e) {
            return ApiResponse.error(400, "invalid_json", "request body is not valid JSON");
        }
        if (body == null || !body.isObject()) {
            return ApiResponse.error(400, "invalid_json", "request body must be a JSON object");
        }
        for (Iterator<String> it = body.fieldNames(); it.hasNext(); ) {
            String f = it.next();
            if (!CREATE_FIELDS.contains(f)) {
                return ApiResponse.error(400, "validation_failed", "unknown field: " + f);
            }
        }
        String url = textField(body, "url");
        String alias = textField(body, "alias");
        ShortenerService.CreateResult result = service.create(url, alias, req.header("idempotency-key"));
        count(result.created() ? "links_created_total" : "idempotent_replays_total");
        return new ApiResponse(result.created() ? 201 : 200, linkBody(result.link()),
                Map.of("Location", "/api/v1/links/" + result.link().code()));
    }

    private static String textField(JsonNode body, String name) {
        JsonNode n = body.get(name);
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isTextual()) {
            throw ServiceException.validation(name + " must be a string");
        }
        return n.asText();
    }

    private Map<String, Object> linkBody(Link link) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", link.code());
        m.put("shortUrl", service.shortUrl(link.code()));
        m.put("targetUrl", link.targetUrl());
        m.put("createdAt", link.createdAt().toString());
        m.put("custom", link.custom());
        return m;
    }

    private static int intParam(URI uri, String name, int fallback) {
        String q = uri.getRawQuery();
        if (q == null) {
            return fallback;
        }
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            if (k.equals(name)) {
                String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                try {
                    return Integer.parseInt(v);
                } catch (NumberFormatException e) {
                    throw ServiceException.validation(name + " must be an integer");
                }
            }
        }
        return fallback;
    }

    private static ApiResponse onlyGet(String method, Supplier<ApiResponse> handler) {
        return method.equals("GET") ? handler.get() : methodNotAllowed("GET");
    }

    private static ApiResponse methodNotAllowed(String allow) {
        return ApiResponse.error(405, "method_not_allowed", "allowed methods: " + allow, Map.of("Allow", allow));
    }

    private void count(String name) {
        counters.computeIfAbsent(name, k -> new LongAdder()).increment();
    }
}
