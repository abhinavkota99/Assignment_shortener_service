package com.example.shortener.health;

import com.example.shortener.store.LinkRepository;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Storage health, part of the readiness group ({@code /actuator/health/readiness}): if the journal can no
 * longer be written, the instance reports DOWN so a load balancer stops routing traffic to it.
 */
@Component("storage")
public class StorageHealthIndicator implements HealthIndicator {

    private final LinkRepository repository;

    public StorageHealthIndicator(LinkRepository repository) {
        this.repository = repository;
    }

    @Override
    public Health health() {
        Health.Builder b = repository.healthy() ? Health.up() : Health.down();
        return b.withDetail("links", repository.linkCount()).build();
    }
}
