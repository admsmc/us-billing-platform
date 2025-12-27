package com.example.usbilling.customer.service

import com.example.usbilling.customer.api.*
import com.example.usbilling.customer.model.*
import com.example.usbilling.customer.repository.JdbcInteractionRepository
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class InteractionRecordingServiceImpl(
    private val interactionRepository: JdbcInteractionRepository,
) : InteractionRecordingService {
    
    @Transactional
    override fun logInteraction(request: LogInteractionRequest): CustomerInteractionDto {
        val interactionId = "interaction-${UUID.randomUUID()}"
        
        val interaction = CustomerInteraction(
            interactionId = interactionId,
            utilityId = UtilityId(request.utilityId),
            accountId = request.accountId,
            customerId = request.customerId?.let { CustomerId(it) },
            interactionType = InteractionType.valueOf(request.interactionType),
            interactionChannel = InteractionChannel.valueOf(request.interactionChannel),
            interactionReason = InteractionReason.valueOf(request.interactionReason),
            direction = InteractionDirection.valueOf(request.direction),
            initiatedBy = request.initiatedBy,
            summary = request.summary,
            details = request.details,
            outcome = request.outcome?.let { InteractionOutcome.valueOf(it) },
            followUpRequired = false,
            followUpDate = null,
            durationSeconds = null,
            timestamp = Instant.now(),
            sentiment = request.sentiment?.let { InteractionSentiment.valueOf(it) },
            tags = emptyList(),
            relatedCaseId = null,
        )
        
        val saved = interactionRepository.insert(interaction)
        return toDto(saved)
    }
    
    private fun toDto(interaction: CustomerInteraction): CustomerInteractionDto {
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
