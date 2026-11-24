package com.example.shortener.web;

import com.example.shortener.config.Settings;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs first on every request: assigns a request id (echoing a sane client-supplied {@code X-Request-ID})
 * for correlation in logs and responses, sets {@code X-Content-Type-Options: nosniff}, and rejects bodies
 * larger than {@code shortener.max-body-bytes} before anything reads them.
 *
 * <p>Named {@code RequestIdFilter}, not {@code RequestContextFilter}: Spring MVC already registers a bean
 * called {@code requestContextFilter}, and Spring Boot refuses bean-definition overriding by default.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    static final String REQUEST_ID = "X-Request-ID";

    private final long maxBodyBytes;

    public RequestIdFilter(Settings settings) {
        this.maxBodyBytes = settings.maxBodyBytes();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader(REQUEST_ID);
        String requestId = supplied != null && supplied.matches("[A-Za-z0-9._-]{1,128}") ? supplied : UUID.randomUUID().toString();
        response.setHeader(REQUEST_ID, requestId);
        response.setHeader("X-Content-Type-Options", "nosniff");
        if (request.getContentLengthLong() > maxBodyBytes) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getOutputStream().write(("{\"error\":{\"code\":\"payload_too_large\",\"message\":\"body exceeds "
                    + maxBodyBytes + " bytes\"}}").getBytes(StandardCharsets.UTF_8));
            return;
        }
        MDC.put("requestId", requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
        }
    }
}
