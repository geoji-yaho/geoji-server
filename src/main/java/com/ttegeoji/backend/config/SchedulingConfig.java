package com.ttegeoji.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// watchdog(10 §6)·reaper(10 §7) 같은 @Scheduled 작업을 켠다. 작업 자체는 각 패키지에 있다.
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
