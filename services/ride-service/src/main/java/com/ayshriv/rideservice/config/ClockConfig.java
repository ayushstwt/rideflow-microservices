package com.ayshriv.rideservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    /** Injectable clock so time-dependent logic can be tested with a fixed instant. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
