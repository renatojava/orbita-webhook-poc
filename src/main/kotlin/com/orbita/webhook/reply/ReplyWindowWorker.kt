package com.orbita.webhook.reply

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class ReplyWindowWorker(private val closer: ReplyWindowCloser) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${orbita.reply-window.poll-interval}")
    fun closeDueWindows() {
        try {
            while (closer.closeNextDue()) {
                // Keep going until no window is due; each close is its own transaction.
            }
        } catch (e: Exception) {
            // The window stays in the table, so the next tick tries again and nothing is lost.
            log.error("Could not close a reply window; will retry", e)
        }
    }
}
