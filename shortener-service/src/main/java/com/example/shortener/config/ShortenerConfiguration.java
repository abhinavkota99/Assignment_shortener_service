package com.example.shortener.config;

import com.example.shortener.core.CodeGenerator;
import com.example.shortener.core.ShortenerService;
import com.example.shortener.core.UrlValidator;
import com.example.shortener.store.InMemoryLinkRepository;
import com.example.shortener.store.JournalLinkRepository;
import com.example.shortener.store.LinkRepository;
import com.example.shortener.web.TokenBucketRateLimiter;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring of the framework-free domain. The core classes stay plain Java (no Spring annotations), so they
 * are unit-testable without a context and reusable outside Spring.
 */
@Configuration(proxyBeanMethods = false)
public class ShortenerConfiguration {

    @Bean(destroyMethod = "close")
    LinkRepository linkRepository(Settings settings) {
        return settings.dataFile() == null
                ? new InMemoryLinkRepository()
                : new JournalLinkRepository(Path.of(settings.dataFile()));
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    UrlValidator urlValidator(Settings settings) {
        return new UrlValidator(settings);
    }

    @Bean
    CodeGenerator codeGenerator(Settings settings) {
        return new CodeGenerator(settings.codeLength());
    }

    @Bean
    ShortenerService shortenerService(LinkRepository repository, Settings settings, UrlValidator validator,
                                      CodeGenerator codes, Clock clock) {
        return new ShortenerService(repository, settings, validator, codes, clock);
    }

    @Bean
    TokenBucketRateLimiter rateLimiter(Settings settings) {
        return new TokenBucketRateLimiter(settings.rateLimitPerSecond(), settings.rateLimitBurst());
    }
}
