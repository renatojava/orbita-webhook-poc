package com.orbita.webhook.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Background workers run unless orbita.workers.enabled=false. Turning them off gives the
 * one-shot migration run, and would also allow webhook-only pods scaled apart from worker pods.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty("orbita.workers.enabled", havingValue = "true", matchIfMissing = true)
class WorkersConfig
