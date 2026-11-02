package com.orbita.webhook.outbound

import com.orbita.webhook.config.OrbitaProperties
import com.orbita.webhook.config.backoffDelay
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Each tenant is served on its own virtual thread and has at most one batch in flight per pod.
 * A tenant with a huge campaign or a slow send only ever waits on itself.
 *
 * Several pods may serve the same tenant at the same time, and that is fine: SKIP LOCKED gives each
 * message to one pod only, and the send limit is a row in Postgres shared by every pod.
 */
@Component
class OutboundDispatcher(
    private val outbound: OutboundRepository,
    private val gateway: WhatsAppGateway,
    properties: OrbitaProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val config = properties.outbound
    private val workerId = properties.instanceId
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    /**
     * Local to this pod, on purpose. It only stops this pod from opening a second batch for a tenant whose
     * first batch is still running. It coordinates nothing between pods and nothing relies on it for that.
     */
    private val servingLocally = ConcurrentHashMap.newKeySet<String>()

    @Scheduled(fixedDelayString = "\${orbita.outbound.poll-interval}")
    fun dispatch() {
        outbound.flagExpiredSendsAsUncertain().also { flagged ->
            if (flagged > 0) log.error("{} outbound messages flagged UNCERTAIN: lease expired while sending", flagged)
        }
        outbound.tenantsWithPendingWork()
            .filter(servingLocally::add)
            .forEach { tenantId ->
                try {
                    executor.submit { serve(tenantId) }
                } catch (e: RejectedExecutionException) {
                    // Shutting down: release the mark, or this pod would skip the tenant for good.
                    servingLocally.remove(tenantId)
                }
            }
    }

    private fun serve(tenantId: String) {
        try {
            val batch = outbound.claimWithinRateLimit(tenantId, config.batchSize, config.lease, workerId)
            executor.invokeAll(batch.map { message -> Callable { send(message) } })
        } catch (e: Exception) {
            log.error("Dispatch failed for tenant {}", tenantId, e)
        } finally {
            servingLocally.remove(tenantId)
        }
    }

    private fun send(message: OutboundMessage) {
        val result = try {
            gateway.send(message)
        } catch (e: Exception) {
            // An unexpected exception tells us nothing about what Meta received.
            SendResult.Unknown("${e.javaClass.simpleName}: ${e.message}")
        }
        when (result) {
            is SendResult.Accepted -> outbound.markSent(message.id, result.providerMessageId)
            is SendResult.Unknown -> {
                outbound.finishAs(message.id, OutboundRepository.STATUS_UNCERTAIN, result.reason)
                log.error("Outbound {} flagged UNCERTAIN: {}", message.id, result.reason)
            }
            is SendResult.Rejected -> retryOrBury(message, result.reason)
        }
    }

    private fun retryOrBury(message: OutboundMessage, reason: String) {
        if (message.attempts >= config.maxAttempts) {
            outbound.finishAs(message.id, OutboundRepository.STATUS_DEAD, reason)
            log.error("Outbound {} moved to dead letter after {} attempts: {}", message.id, message.attempts, reason)
        } else {
            val delay = backoffDelay(message.attempts, config.backoffBase, config.backoffMax)
            outbound.scheduleRetry(message.id, delay, reason)
        }
    }

    @PreDestroy
    fun shutdown() = executor.close()
}
