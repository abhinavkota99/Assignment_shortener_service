package com.example.shortener.api;

import java.util.LinkedHashMap;
import java.util.Map;

/** Transport-neutral HTTP response. {@code body} is serialised as JSON when non-null. */
public record ApiResponse(int status, Object body, Map<String, String> headers) {

    public ApiResponse {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public static ApiResponse json(int status, Object body) {
        return new ApiResponse(status, body, Map.of());
    }

    public static ApiResponse error(int status, String code, String message) {
        return error(status, code, message, Map.of());
    }

    public static ApiResponse error(int status, String code, String message, Map<String, String> headers) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", message);
        return new ApiResponse(status, Map.of("error", err), headers);
    }

    public ApiResponse withHeader(String name, String value) {
        Map<String, String> h = new LinkedHashMap<>(headers);
        h.put(name, value);
        return new ApiResponse(status, body, h);
    }
}
