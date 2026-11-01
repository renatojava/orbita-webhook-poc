package com.orbita.webhook.inbound

import com.orbita.webhook.config.OrbitaProperties
import com.orbita.webhook.config.backoffDelay
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class InboundWorker(
    private val deliveries: DeliveryRepository,
    private val processor: DeliveryProcessor,
    properties: OrbitaProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val config = properties.inbound

    @Scheduled(fixedDelayString = "\${orbita.inbound.poll-interval}")
    fun drain() {
        deliveries.buryExhaustedLeases(config.maxAttempts).also { buried ->
            if (buried > 0) log.error("{} deliveries moved to dead letter after losing their last lease", buried)
        }
        do {
            val batch = deliveries.claim(config.batchSize, config.lease)
            batch.forEach(::handle)
        } while (batch.size == config.batchSize)
    }

    private fun handle(delivery: Delivery) {
        try {
            processor.process(delivery)
        } catch (e: LeaseLostException) {
            log.warn(e.message)
        } catch (e: Exception) {
            fail(delivery, e)
        }
    }

    private fun fail(delivery: Delivery, cause: Exception) {
        val error = "${cause.javaClass.simpleName}: ${cause.message}".take(MAX_ERROR_LENGTH)
        if (delivery.attempts >= config.maxAttempts) {
            deliveries.markDead(delivery, error)
            log.error("Delivery {} moved to dead letter after {} attempts: {}", delivery.id, delivery.attempts, error)
        } else {
            val delay = backoffDelay(delivery.attempts, config.backoffBase, config.backoffMax)
            deliveries.scheduleRetry(delivery, delay, error)
            log.warn("Delivery {} failed on attempt {}, retrying in {}: {}", delivery.id, delivery.attempts, delay, error)
        }
    }

    private companion object {
        const val MAX_ERROR_LENGTH = 1000
    }
}
