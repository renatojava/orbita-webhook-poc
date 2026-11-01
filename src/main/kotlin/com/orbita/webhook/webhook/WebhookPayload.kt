package com.orbita.webhook.webhook

import com.fasterxml.jackson.annotation.JsonProperty

data class WebhookPayload(val entry: List<Entry> = emptyList()) {
    data class Entry(val changes: List<Change> = emptyList())

    data class Change(val field: String, val value: Value)

    data class Value(
        val metadata: Metadata,
        val contacts: List<Contact> = emptyList(),
        val messages: List<Message> = emptyList(),
    )

    data class Metadata(@param:JsonProperty("phone_number_id") val phoneNumberId: String)

    data class Contact(val profile: Profile? = null, @param:JsonProperty("wa_id") val waId: String)

    data class Profile(val name: String? = null)

    data class Message(
        val from: String,
        val id: String,
        val timestamp: Long,
        val type: String,
        val text: Text? = null,
    )

    data class Text(val body: String)
}
