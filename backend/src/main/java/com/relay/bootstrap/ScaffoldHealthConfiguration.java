package com.relay.bootstrap;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** A database-free scaffold must never advertise that it can accept workflow work. */
@Configuration(proxyBeanMethods = false)
@Profile("scaffold")
public class ScaffoldHealthConfiguration {
    @Bean
    HealthIndicator dbHealthIndicator() {
        return () -> Health.outOfService().build();
    }
}
