package com.example.shortener.web;

import com.example.shortener.core.Link;
import com.example.shortener.core.LinkStats;
import com.example.shortener.core.ServiceException;
import com.example.shortener.core.ShortenerService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP contract of the shortener. Thin by design: it maps HTTP to {@link ShortenerService} calls; all
 * business rules live in the service, and all error mapping in {@link ApiExceptionHandler}.
 *
 * <pre>
 *   POST  /api/v1/links               create a short link                201, 200 (idempotent replay), 400, 409, 429
 *   GET   /api/v1/links/{code}        link metadata                      200, 404
 *   GET   /api/v1/links/{code}/stats  click analytics (?days=1..365)     200, 400, 404
 *   GET   /{code}                     redirect and record a click        302, 404
 *   HEAD  /{code}                     redirect, no click recorded        302, 404
 *   GET   /actuator/health            liveness and readiness probes      200, 503
 *   GET   /actuator/metrics           request and business metrics       200
 * </pre>
 */
@RestController
public class LinkController {

    static final Set<String> CREATE_FIELDS = Set.of("url", "alias");

    private final ShortenerService service;
    private final MeterRegistry metrics;

    public LinkController(ShortenerService service, MeterRegistry metrics) {
        this.service = service;
        this.metrics = metrics;
    }

    @PostMapping(path = "/api/v1/links", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LinkResponse> create(@RequestBody Map<String, Object> body,
                                               @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        for (String field : body.keySet()) {
            if (!CREATE_FIELDS.contains(field)) {
                throw ServiceException.validation("unknown field: " + field);
            }
        }
        String url = text(body, "url");
        String alias = text(body, "alias");
        ShortenerService.CreateResult result = service.create(url, alias, idempotencyKey);
        metrics.counter("shortener.links.created", "outcome", result.created() ? "created" : "idempotent_replay").increment();
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .header(HttpHeaders.LOCATION, "/api/v1/links/" + result.link().code())
                .body(response(result.link()));
    }

    @GetMapping(path = "/api/v1/links/{code}", produces = MediaType.APPLICATION_JSON_VALUE)
    public LinkResponse get(@PathVariable String code) {
        return response(service.get(code));
    }

    @GetMapping(path = "/api/v1/links/{code}/stats", produces = MediaType.APPLICATION_JSON_VALUE)
    public LinkStats stats(@PathVariable String code, @RequestParam(defaultValue = "30") int days) {
        return service.stats(code, days);
    }

    /**
     * 302 rather than 301 so browsers never cache the redirect: every click reaches the service and is
     * counted. HEAD (link unfurlers, crawlers) is answered by the same mapping but not counted.
     */
    @GetMapping("/{code:[A-Za-z0-9][A-Za-z0-9_-]+}")
    public ResponseEntity<Void> redirect(@PathVariable String code, HttpServletRequest request) {
        boolean countClick = "GET".equals(request.getMethod());
        Link link = service.resolve(code, request.getHeader(HttpHeaders.REFERER), request.getHeader(HttpHeaders.USER_AGENT), countClick);
        metrics.counter("shortener.redirects", "counted", Boolean.toString(countClick)).increment();
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, link.targetUrl())
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=0")
                .build();
    }

    private LinkResponse response(Link link) {
        return LinkResponse.of(link, service.shortUrl(link.code()));
    }

    private static String text(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String s)) {
            throw ServiceException.validation(field + " must be a string");
        }
        return s;
    }
}
