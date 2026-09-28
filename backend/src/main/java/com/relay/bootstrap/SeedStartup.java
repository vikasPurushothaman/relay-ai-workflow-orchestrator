package com.relay.bootstrap;

import com.relay.api.SeedLoader;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="relay.seeds.enabled",havingValue="true")
public final class SeedStartup implements ApplicationRunner {
    private final SeedLoader loader;
    public SeedStartup(SeedLoader loader) { this.loader=loader; }
    @Override public void run(ApplicationArguments args) {
        try {
            var result=loader.load();
            org.slf4j.LoggerFactory.getLogger(SeedStartup.class).info("Workflow seeds: created={}, preserved={}",result.created(),result.preserved());
        } catch(RuntimeException e) {
            throw new IllegalStateException("Workflow seed startup failed; verify bundled resources and database access");
        }
    }
}
