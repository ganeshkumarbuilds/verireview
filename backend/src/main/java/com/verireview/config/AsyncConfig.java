package com.verireview.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Bounded async execution for analysis jobs (never inline on web threads). */
@Configuration
@EnableAsync
public class AsyncConfig {

  @Bean("analysisExecutor")
  Executor analysisExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(4);
    executor.setQueueCapacity(50);
    executor.setThreadNamePrefix("analysis-");
    executor.initialize();
    return executor;
  }
}
