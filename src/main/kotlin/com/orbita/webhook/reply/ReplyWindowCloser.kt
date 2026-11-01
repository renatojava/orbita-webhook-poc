package com.orbita.webhook.reply

import com.orbita.webhook.outbound.OutboundRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ReplyWindowCloser(
    private val windows: ReplyWindowRepository,
    private val outbound: OutboundRepository,
) {
    /**
     * Reply, links and window removal commit together, so a crash means the whole window runs again.
     * The key comes from the oldest message: a message belongs to a single batch, so it cannot repeat.
     * This is where the real agent would receive all the messages as one prompt.
     * Returns false when no window is due.
     */
    @Transactional
    fun closeNextDue(): Boolean {
        val window = windows.lockNextDue() ?: return false
        val messages = windows.unansweredMessages(window)
        if (messages.isNotEmpty()) {
            val replyKey = "auto-reply:${messages.first().wamid}"
            outbound.enqueue(
                tenantId = window.tenantId,
                idempotencyKey = replyKey,
                priority = OutboundRepository.PRIORITY_REPLY,
                toPhone = window.contactPhone,
                body = AUTO_REPLY,
            )
            windows.linkToReply(messages.map { it.id }, replyKey)
        }
        windows.delete(window)
        return true
    }

    private companion object {
        const val AUTO_REPLY = "Recebemos sua mensagem. Um atendente vai falar com você em breve."
    }
}
