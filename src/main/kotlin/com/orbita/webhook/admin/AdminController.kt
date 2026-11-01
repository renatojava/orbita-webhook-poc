package com.orbita.webhook.admin

import com.orbita.webhook.inbound.DeliveryRepository
import com.orbita.webhook.outbound.OutboundRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class TenantRequest(val phoneNumberId: String, val sendRatePerSecond: Double, val sendBurst: Int)

data class CampaignRequest(val campaignId: String, val count: Int, val body: String)

data class CampaignResponse(val enqueued: Int)

/** Operations surface for the demo. It has no authentication: see "O que ficou de fora" in the README. */
@RestController
@RequestMapping("/internal")
class AdminController(
    private val admin: AdminRepository,
    private val outbound: OutboundRepository,
    private val deliveries: DeliveryRepository,
) {
    @PutMapping("/tenants/{id}")
    fun upsertTenant(@PathVariable id: String, @RequestBody request: TenantRequest): ResponseEntity<Void> {
        admin.upsertTenant(id, request.phoneNumberId, request.sendRatePerSecond, request.sendBurst)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/tenants/{id}/campaigns")
    fun startCampaign(@PathVariable id: String, @RequestBody request: CampaignRequest) =
        CampaignResponse(outbound.enqueueCampaign(id, request.campaignId, request.count, request.body))

    @GetMapping("/reconciliation")
    fun reconciliation(): Reconciliation = admin.reconcile()

    @GetMapping("/dead-letters")
    fun deadLetters(): List<DeadLetter> = admin.deadLetters()

    @PostMapping("/dead-letters/deliveries/{id}/replay")
    fun replay(@PathVariable id: Long): ResponseEntity<Void> =
        if (deliveries.replay(id)) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()
}
