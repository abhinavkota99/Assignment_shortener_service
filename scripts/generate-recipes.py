#!/usr/bin/env python3
"""Authoring helper for the reviewed brownfield change sets in knowledge/recipes/.

Run from the repository root. Every patch anchor is checked to occur exactly once in the current
shortener-service source before the JSON is written, so recipes cannot be authored against stale code.
(At run time the orchestrator re-checks the same invariant and treats drift as a conflict.)
"""
import json, pathlib
root = pathlib.Path('shortener-service')
P = 'src/main/java/com/example/shortener/'
def check(path, ops):
    text = (root/path).read_text()
    for op in ops:
        if op['op'] == 'patch':
            n = text.count(op['find'])
            assert n == 1, (path, n, op['find'][:60])
            text = text.replace(op['find'], op['replace'])
    return text
def patch(path, find, replace): return {"op":"patch","path":path,"find":find,"replace":replace}

link = P+'core/Link.java'
sexc = P+'core/ServiceException.java'
svc = P+'core/ShortenerService.java'
domain = [
 patch(link, """/** A short code mapped to a destination URL. Immutable. */
public record Link(String code, String targetUrl, Instant createdAt, boolean custom) {

    public Link {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(targetUrl, "targetUrl");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}""", """/** A short code mapped to a destination URL, optionally expiring at {@code expiresAt}. Immutable. */
public record Link(String code, String targetUrl, Instant createdAt, boolean custom, Instant expiresAt) {

    public Link {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(targetUrl, "targetUrl");
        Objects.requireNonNull(createdAt, "createdAt");
        if (expiresAt != null && !expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
    }

    /** Source-compatible constructor for existing callers: links without an expiry never expire. */
    public Link(String code, String targetUrl, Instant createdAt, boolean custom) {
        this(code, targetUrl, createdAt, custom, null);
    }

    /** Expired from the expiry instant onwards (inclusive). */
    public boolean isExpiredAt(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }
}"""),
 patch(sexc, '        CONFLICT(409, "conflict"),\n', '        CONFLICT(409, "conflict"),\n        GONE(410, "gone"),\n'),
 patch(svc, 'import java.time.Clock;\n', 'import java.time.Clock;\nimport java.time.Duration;\n'),
 patch(svc, '    static final int MAX_HEADER_VALUE_LENGTH = 512;\n', '''    static final int MAX_HEADER_VALUE_LENGTH = 512;
    /** Upper bound on link lifetime, so a typo cannot create an effectively permanent "temporary" link. */
    public static final Duration MAX_LIFETIME = Duration.ofDays(5 * 365L);
'''),
 patch(svc, '    public CreateResult create(String url, String alias, String idempotencyKey) {\n', '''    public CreateResult create(String url, String alias, String idempotencyKey) {
        return create(url, alias, idempotencyKey, null);
    }

    /** Creates a link that stops redirecting at {@code expiresAt} ({@code null} = never expires). */
    public CreateResult create(String url, String alias, String idempotencyKey, Instant expiresAt) {
'''),
 patch(svc, '        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);\n', '''        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        if (expiresAt != null) {
            if (!expiresAt.isAfter(now)) {
                throw ServiceException.validation("expiresAt must be in the future");
            }
            if (expiresAt.isAfter(now.plus(MAX_LIFETIME))) {
                throw ServiceException.validation("expiresAt must be at most " + MAX_LIFETIME.toDays() + " days ahead");
            }
        }
'''),
 patch(svc, 'Link link = new Link(alias, target, now, true);', 'Link link = new Link(alias, target, now, true, expiresAt);'),
 patch(svc, 'Link link = new Link(codes.next(), target, now, false);', 'Link link = new Link(codes.next(), target, now, false, expiresAt);'),
 patch(svc, '''    /** Looks up the target and, if {@code record}, logs a click. */
    public Link resolve(String code, String referrer, String userAgent, boolean record) {
        Link link = get(code);
''', '''    /** Looks up the target and, if {@code record}, logs a click. Expired links are GONE and not counted. */
    public Link resolve(String code, String referrer, String userAgent, boolean record) {
        Link link = get(code);
        if (link.isExpiredAt(clock.instant())) {
            throw new ServiceException(ServiceException.Kind.GONE, "link '" + code + "' expired at " + link.expiresAt());
        }
'''),
]
for f in (link, sexc, svc):
    check(f, [o for o in domain if o['path']==f])

jr = P+'store/JournalLinkRepository.java'
store = [
 patch(jr, '''                .put("createdAt", link.createdAt().toString()).put("custom", link.custom());
''', '''                .put("createdAt", link.createdAt().toString()).put("custom", link.custom());
        if (link.expiresAt() != null) {
            n.put("expiresAt", link.expiresAt().toString()); // optional field: older records simply lack it
        }
'''),
 patch(jr, '''                            Instant.parse(n.get("createdAt").asText()), n.path("custom").asBoolean()),''', '''                            Instant.parse(n.get("createdAt").asText()), n.path("custom").asBoolean(),
                            n.hasNonNull("expiresAt") ? Instant.parse(n.get("expiresAt").asText()) : null),'''),
]
check(jr, store)

sa = P+'api/ShortenerApi.java'
api = [
 patch(sa, ''' *   GET   /{code}                     redirect and record a click        302, 404
 *   HEAD  /{code}                     redirect, no click recorded        302, 404
''', ''' *   GET   /{code}                     redirect and record a click        302, 404, 410 (expired)
 *   HEAD  /{code}                     redirect, no click recorded        302, 404, 410 (expired)
'''),
 patch(sa, 'import java.nio.charset.StandardCharsets;\n', 'import java.nio.charset.StandardCharsets;\nimport java.time.Instant;\nimport java.time.format.DateTimeParseException;\n'),
 patch(sa, 'Set.of("url", "alias");', 'Set.of("url", "alias", "expiresAt");'),
 patch(sa, '''        ShortenerService.CreateResult result = service.create(url, alias, req.header("idempotency-key"));''',
           '''        Instant expiresAt = parseInstant(textField(body, "expiresAt"));
        ShortenerService.CreateResult result = service.create(url, alias, req.header("idempotency-key"), expiresAt);'''),
 patch(sa, '''    private Map<String, Object> linkBody(Link link) {''', '''    private static Instant parseInstant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw ServiceException.validation("expiresAt must be an ISO-8601 UTC timestamp, e.g. 2026-12-31T23:59:59Z");
        }
    }

    private Map<String, Object> linkBody(Link link) {'''),
 patch(sa, '        m.put("custom", link.custom());\n', '        m.put("custom", link.custom());\n        m.put("expiresAt", link.expiresAt() == null ? null : link.expiresAt().toString());\n'),
]
check(sa, api)

T = 'src/test/java/com/example/shortener/'
tests = [
 {"op":"create","path":T+'core/LinkExpirationTest.java',"contentFile":"LinkExpirationTest.java"},
 {"op":"create","path":T+'api/LinkExpirationApiTest.java',"contentFile":"LinkExpirationApiTest.java"},
 {"op":"create","path":T+'store/JournalExpiryCompatibilityTest.java',"contentFile":"JournalExpiryCompatibilityTest.java"},
]
d = pathlib.Path('knowledge/recipes/link-expiration')
for name, ops, desc in [('domain', domain, 'Link gains optional expiresAt (source-compatible constructor kept); GONE error kind; service validates and enforces expiry'),
                        ('store', store, 'Journal writes optional expiresAt and reads records with or without it'),
                        ('api', api, 'expiresAt request/response field, ISO-8601 parsing, 410 documented in route table'),
                        ('tests', tests, 'Service, API and journal-compatibility tests for expiry')]:
    (d/f'{name}.json').write_text(json.dumps({"description":desc,"ops":ops}, indent=2)+"\n")

app = P+'ShortenerApplication.java'
wiring = [
 patch(app, 'import com.example.shortener.config.Settings;\n', 'import com.example.shortener.config.Settings;\nimport com.example.shortener.core.CodeGenerator;\nimport com.example.shortener.core.DenylistUrlValidator;\n'),
 patch(app, 'import java.nio.file.Path;\n', 'import java.nio.file.Path;\nimport java.time.Clock;\n'),
 patch(app, '        ShortenerService service = new ShortenerService(repository, settings);\n',
            '''        // SHORTENER_BLOCKED_HOSTS: comma-separated destination domains (subdomains included) that links may not target.
        ShortenerService service = new ShortenerService(repository, settings,
                DenylistUrlValidator.fromEnv(settings, System.getenv()), new CodeGenerator(settings.codeLength()), Clock.systemUTC());
'''),
]
check(app, wiring)
d = pathlib.Path('knowledge/recipes/destination-denylist')
(d/'validator.json').write_text(json.dumps({"description":"UrlValidator subclass enforcing a configurable domain denylist via the checkHost extension point","ops":[
 {"op":"create","path":P+'core/DenylistUrlValidator.java',"contentFile":"DenylistUrlValidator.java"},
 {"op":"create","path":T+'core/DenylistUrlValidatorTest.java',"contentFile":"DenylistUrlValidatorTest.java"}]}, indent=2)+"\n")
(d/'wiring.json').write_text(json.dumps({"description":"Composition root builds the service with the denylist validator from SHORTENER_BLOCKED_HOSTS","ops":wiring}, indent=2)+"\n")
print("ok")
