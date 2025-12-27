package com.example.usbilling.customer.http

import com.example.usbilling.customer.api.*
import com.example.usbilling.customer.repository.JdbcInteractionRepository
import com.example.usbilling.shared.CustomerId
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/utilities/{utilityId}/interactions")
class InteractionController(
    private val interactionService: InteractionRecordingService,
    private val interactionRepository: JdbcInteractionRepository,
) {
    
    /**
     * Log a new customer interaction.
     * POST /api/v1/utilities/{utilityId}/interactions
     */
    @PostMapping
    fun logInteraction(
        @PathVariable utilityId: String,
        @RequestBody request: LogInteractionRequest,
    ): ResponseEntity<CustomerInteractionDto> {
        val interaction = interactionService.logInteraction(request)
        return ResponseEntity.status(HttpStatus.CREATED).body(interaction)
    }
    
    /**
     * Get interaction history by account.
     * GET /api/v1/utilities/{utilityId}/interactions?accountId=...
     */
    @GetMapping
    fun getInteractions(
        @PathVariable utilityId: String,
        @RequestParam(required = false) accountId: String?,
        @RequestParam(required = false) customerId: String?,
        @RequestParam(defaultValue = "100") limit: Int,
    ): ResponseEntity<List<CustomerInteractionDto>> {
        val interactions = when {
            accountId != null -> interactionRepository.findByAccount(accountId, limit)
            customerId != null -> interactionRepository.findByCustomer(CustomerId(customerId), limit)
            else -> interactionRepository.findByUtility(com.example.usbilling.shared.UtilityId(utilityId), limit)
        }
        
        return ResponseEntity.ok(interactions.map { toDto(it) })
    }
    
    /**
     * Get specific interaction details.
     * GET /api/v1/utilities/{utilityId}/interactions/{interactionId}
     */
    @GetMapping("/{interactionId}")
    fun getInteraction(
        @PathVariable utilityId: String,
        @PathVariable interactionId: String,
    ): ResponseEntity<CustomerInteractionDto> {
        val interaction = interactionRepository.findById(interactionId)
            ?: return ResponseEntity.notFound().build()
        
        return ResponseEntity.ok(toDto(interaction))
    }
    
    private fun toDto(interaction: com.example.usbilling.customer.model.CustomerInteraction): CustomerInteractionDto {
        return CustomerInteractionDto(
            interactionId = interaction.interactionId,
            utilityId = interaction.utilityId.value,
            accountId = interaction.accountId,
            customerId = interaction.customerId?.value,
            interactionType = interaction.interactionType.name,
            interactionChannel = interaction.interactionChannel.name,
            interactionReason = interaction.interactionReason.name,
            direction = interaction.direction.name,
            summary = interaction.summary,
            outcome = interaction.outcome?.name,
            timestamp = interaction.timestamp,
            initiatedBy = interaction.initiatedBy,
        )
    }
}
