package com.example.shortener.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata for the generated document ({@code /v3/api-docs}) and Swagger UI
 * ({@code /swagger-ui.html}). The server entry uses {@code shortener.base-url}, so "Try it out" calls the
 * instance being configured.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    @Bean
    OpenAPI shortenerOpenApi(Settings settings) {
        return new OpenAPI()
                .info(new Info()
                        .title("URL Shortener API")
                        .version("v1")
                        .description("Create short links (optionally with a custom alias and an Idempotency-Key), "
                                + "redirect with click tracking, and read per-link analytics. All errors use "
                                + "{\"error\":{\"code\":\"...\",\"message\":\"...\"}}.")
                        .license(new License().name("Apache 2.0")))
                .servers(List.of(new Server().url(settings.baseUrl()).description("configured base URL")));
    }
}
