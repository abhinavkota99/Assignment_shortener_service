package com.example.shortener.web;

import com.example.shortener.core.Link;
import com.example.shortener.core.LinkStats;
import com.example.shortener.core.ServiceException;
import com.example.shortener.core.ShortenerService;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 *   GET   /swagger-ui.html            interactive API docs (Swagger UI)  200
 *   GET   /v3/api-docs                OpenAPI 3 document                 200
 * </pre>
 */
@RestController
@Tag(name = "Links", description = "Create short links, follow them, and read their click analytics")
public class LinkController {

    static final Set<String> CREATE_FIELDS = Set.of("url", "alias");

    private final ShortenerService service;
    private final MeterRegistry metrics;

    public LinkController(ShortenerService service, MeterRegistry metrics) {
        this.service = service;
        this.metrics = metrics;
    }

    @Operation(summary = "Create a short link",
            description = "Validates the destination URL and returns a generated 7-character code, or the custom alias if "
                    + "one is given. Sending the same Idempotency-Key again returns the original link (200) instead of a duplicate.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(type = "object", requiredProperties = {"url"}),
                    examples = {
                        @ExampleObject(name = "generated code", value = "{\"url\":\"https://example.com/docs\"}"),
                        @ExampleObject(name = "custom alias", value = "{\"url\":\"https://example.com/docs\",\"alias\":\"docs\"}")})))
    @ApiResponse(responseCode = "201", description = "link created")
    @ApiResponse(responseCode = "200", description = "idempotent replay: the link created earlier with this Idempotency-Key")
    @ApiResponse(responseCode = "400", description = "invalid JSON, unknown field, or unsafe/invalid URL or alias",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "alias already taken", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "429", description = "rate limited; see Retry-After", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping(path = "/api/v1/links", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LinkResponse> create(@RequestBody Map<String, Object> body,
                                               @Parameter(in = ParameterIn.HEADER, description = "client-chosen key (1-128 chars) that makes retries safe")
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

    @Operation(summary = "Get a link's metadata")
    @ApiResponse(responseCode = "200", description = "the link")
    @ApiResponse(responseCode = "404", description = "unknown code", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping(path = "/api/v1/links/{code}", produces = MediaType.APPLICATION_JSON_VALUE)
    public LinkResponse get(@Parameter(example = "docs") @PathVariable String code) {
        return response(service.get(code));
    }

    @Operation(summary = "Click analytics for a link",
            description = "Total clicks, last click time, clicks per UTC day over the window, and the top 10 referrers.")
    @ApiResponse(responseCode = "200", description = "analytics")
    @ApiResponse(responseCode = "400", description = "days outside 1..365", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "unknown code", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping(path = "/api/v1/links/{code}/stats", produces = MediaType.APPLICATION_JSON_VALUE)
    public LinkStats stats(@Parameter(example = "docs") @PathVariable String code,
                           @Parameter(description = "trailing window in days (1-365)") @RequestParam(defaultValue = "30") int days) {
        return service.stats(code, days);
    }

    /**
     * 302 rather than 301 so browsers never cache the redirect: every click reaches the service and is
     * counted. HEAD (link unfurlers, crawlers) is answered by the same mapping but not counted.
     */
    @Operation(summary = "Follow a short link",
            description = "Responds 302 with Location set to the destination and records a click (HEAD is not counted). "
                    + "Swagger UI's browser fetch follows the redirect to the external site, which usually fails on CORS; "
                    + "use curl -i to see the 302 itself.")
    @ApiResponse(responseCode = "302", description = "redirect to the destination URL")
    @ApiResponse(responseCode = "404", description = "unknown code", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/{code:[A-Za-z0-9][A-Za-z0-9_-]+}")
    public ResponseEntity<Void> redirect(@Parameter(example = "docs") @PathVariable String code,
                                         @Parameter(hidden = true) HttpServletRequest request) {
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
