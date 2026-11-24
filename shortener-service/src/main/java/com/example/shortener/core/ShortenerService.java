package com.example.shortener.core;

import com.example.shortener.config.Settings;
import com.example.shortener.store.DuplicateKeyException;
import com.example.shortener.store.LinkRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Domain logic for creating, resolving and reporting on short links. Transport-agnostic. */
public class ShortenerService {

    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    static final int MAX_HEADER_VALUE_LENGTH = 512;

    /** Outcome of a create call; {@code created == false} means an idempotent replay. */
    public record CreateResult(Link link, boolean created) { }

    private final LinkRepository repository;
    private final Settings settings;
    private final UrlValidator validator;
    private final CodeGenerator codes;
    private final Clock clock;

    public ShortenerService(LinkRepository repository, Settings settings, UrlValidator validator, CodeGenerator codes, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.settings = Objects.requireNonNull(settings);
        this.validator = Objects.requireNonNull(validator);
        this.codes = Objects.requireNonNull(codes);
        this.clock = Objects.requireNonNull(clock);
    }

    public ShortenerService(LinkRepository repository, Settings settings) {
        this(repository, settings, new UrlValidator(settings), new CodeGenerator(settings.codeLength()), Clock.systemUTC());
    }

    public CreateResult create(String url, String alias, String idempotencyKey) {
        if (idempotencyKey != null) {
            if (idempotencyKey.isEmpty() || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
                throw ServiceException.validation("Idempotency-Key must be 1-" + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
            }
            Optional<Link> replay = repository.findByIdempotencyKey(idempotencyKey);
            if (replay.isPresent()) {
                return new CreateResult(replay.get(), false);
            }
        }
        String target = validator.validate(url);
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);

        if (alias != null) {
            String problem = CodeGenerator.aliasProblem(alias);
            if (problem != null) {
                throw ServiceException.validation(problem);
            }
            Link link = new Link(alias, target, now, true);
            try {
                repository.insert(link, idempotencyKey);
                return new CreateResult(link, true);
            } catch (DuplicateKeyException e) {
                return resolveDuplicate(e, idempotencyKey, () -> new ServiceException(
                        ServiceException.Kind.CONFLICT, "alias '" + alias + "' is already taken"));
            }
        }

        for (int attempt = 0; attempt < settings.maxCodeAttempts(); attempt++) {
            Link link = new Link(codes.next(), target, now, false);
            try {
                repository.insert(link, idempotencyKey);
                return new CreateResult(link, true);
            } catch (DuplicateKeyException e) {
                if (e.field() == DuplicateKeyException.Field.IDEMPOTENCY_KEY) {
                    return resolveDuplicate(e, idempotencyKey, () -> new IllegalStateException(e));
                }
                // code collision: retry with a fresh random code
            }
        }
        throw new ServiceException(ServiceException.Kind.UNAVAILABLE, "could not allocate a unique code; please retry");
    }

    /** A concurrent request with the same idempotency key won the race: return its result. */
    private CreateResult resolveDuplicate(DuplicateKeyException e, String idempotencyKey,
                                          Supplier<RuntimeException> otherwise) {
        if (e.field() == DuplicateKeyException.Field.IDEMPOTENCY_KEY && idempotencyKey != null) {
            Optional<Link> winner = repository.findByIdempotencyKey(idempotencyKey);
            if (winner.isPresent()) {
                return new CreateResult(winner.get(), false);
            }
        }
        throw otherwise.get();
    }

    /** Looks up the target and, if {@code record}, logs a click. */
    public Link resolve(String code, String referrer, String userAgent, boolean record) {
        Link link = get(code);
        if (record) {
            repository.recordClick(new ClickEvent(code, clock.instant(), clip(referrer), clip(userAgent)));
        }
        return link;
    }

    public Link get(String code) {
        return repository.find(code).orElseThrow(() -> ServiceException.notFound("no link for code '" + code + "'"));
    }

    public LinkStats stats(String code, int days) {
        Link link = get(code);
        if (days < 1 || days > 365) {
            throw ServiceException.validation("days must be between 1 and 365");
        }
        List<ClickEvent> clicks = repository.clicks(code);
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate from = today.minusDays(days - 1L);

        Map<LocalDate, Long> perDay = new TreeMap<>(clicks.stream()
                .map(c -> LocalDate.ofInstant(c.at(), ZoneOffset.UTC))
                .filter(d -> !d.isBefore(from) && !d.isAfter(today))
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting())));

        List<LinkStats.Count> referrers = clicks.stream()
                .collect(Collectors.groupingBy(c -> c.referrer() == null ? "(direct)" : c.referrer(), Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey()))
                .limit(10)
                .map(e -> new LinkStats.Count(e.getKey(), e.getValue()))
                .toList();

        String last = clicks.isEmpty() ? null : clicks.get(clicks.size() - 1).at().toString();
        return new LinkStats(link.code(), clicks.size(), last,
                perDay.entrySet().stream().map(e -> new LinkStats.Count(e.getKey().toString(), e.getValue())).toList(),
                referrers, days);
    }

    public String shortUrl(String code) {
        return settings.baseUrl() + "/" + code;
    }

    public boolean healthy() {
        return repository.healthy();
    }

    static String clip(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return value.length() <= MAX_HEADER_VALUE_LENGTH ? value : value.substring(0, MAX_HEADER_VALUE_LENGTH);
    }
}
