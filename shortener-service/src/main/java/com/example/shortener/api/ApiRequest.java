package com.example.shortener.api;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Transport-neutral HTTP request. Header names are lower-cased. */
public record ApiRequest(String method, String rawPath, Map<String, String> headers, byte[] body, String clientId) {

    public ApiRequest {
        Objects.requireNonNull(method);
        Objects.requireNonNull(rawPath);
        headers = headers == null ? Map.of() : headers.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(e -> e.getKey().toLowerCase(Locale.ROOT), Map.Entry::getValue, (a, b) -> a));
        body = body == null ? new byte[0] : body;
        clientId = clientId == null ? "unknown" : clientId;
    }

    public String header(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }
}
