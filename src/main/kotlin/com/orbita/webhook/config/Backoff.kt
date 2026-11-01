package com.orbita.webhook.config

import java.time.Duration
import kotlin.random.Random

/** Exponential backoff with jitter, so a burst of failures does not retry in lockstep. */
fun backoffDelay(attempt: Int, base: Duration, max: Duration, random: Random = Random.Default): Duration {
    val exponent = (attempt - 1).coerceIn(0, 30)
    val ceiling = minOf(base.toMillis() shl exponent, max.toMillis())
    val jittered = ceiling / 2 + random.nextLong(ceiling / 2 + 1)
    return Duration.ofMillis(jittered)
}
