package com.rescuemesh.server.sync;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sync support beans. The {@link Clock} is the server clock used for the
 * batch future/expired checks and ack timestamps; production uses the system
 * clock, tests pin it so the fixed §3 cross-side test vector (created in
 * Nov 2023) can be exercised deterministically.
 */
@Configuration
public class SyncConfig {

    @Bean
    Clock syncClock() {
        return Clock.systemUTC();
    }
}
