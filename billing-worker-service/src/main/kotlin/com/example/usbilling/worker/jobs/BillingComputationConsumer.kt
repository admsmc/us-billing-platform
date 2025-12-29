package com.example.usbilling.worker.jobs

import com.example.usbilling.billing.model.BillResult
import com.example.usbilling.messaging.jobs.BillComputationCompletedEvent
import com.example.usbilling.messaging.jobs.BillingComputationJobRouting
import com.example.usbilling.messaging.jobs.ComputeBillJob
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import com.example.usbilling.worker.service.BillingComputationService
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.*

/**
 * Consumes billing computation jobs from RabbitMQ and publishes results.
 */
@Component
class BillingComputationConsumer(
    private val billingComputationService: BillingComputationService,
    private val rabbitTemplate: RabbitTemplate,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Consume ComputeBillJob messages and compute bills.
     */
    @RabbitListener(queues = [BillingComputationJobRouting.COMPUTE_BILL])
    fun handleComputeBillJob(job: ComputeBillJob) {
        logger.info("Received ComputeBillJob for bill ${job.billId} (messageId: ${job.messageId}, attempt: ${job.attempt})")
        
        try {
            // Compute the bill
            val billResult = billingComputationService.computeBill(
                utilityId = UtilityId(job.utilityId),
                customerId = CustomerId(job.customerId),
                billingPeriodId = job.billingPeriodId,
                serviceState = job.serviceState
            )
            
            if (billResult != null) {
                // Publish success event
                publishCompletionEvent(job, billResult, success = true)
                logger.info("Successfully computed bill ${job.billId}")
            } else {
                // Publish failure event
                publishCompletionEvent(job, null, success = false, errorMessage = "Bill computation returned null")
                logger.error("Bill computation returned null for bill ${job.billId}")
            }
        } catch (e: Exception) {
            logger.error("Failed to compute bill ${job.billId}: ${e.message}", e)
            // Publish failure event
            publishCompletionEvent(job, null, success = false, errorMessage = e.message ?: "Unknown error")
            // Rethrow to trigger RabbitMQ retry/DLQ
            throw e
        }
    }
    
    /**
     * Publish a BillComputationCompletedEvent to notify the orchestrator.
     */
    private fun publishCompletionEvent(
        job: ComputeBillJob,
        billResult: BillResult?,
        success: Boolean,
        errorMessage: String? = null
    ) {
        try {
            val event = BillComputationCompletedEvent(
                messageId = UUID.randomUUID().toString(),
                billId = job.billId,
                utilityId = job.utilityId,
                customerId = job.customerId,
                success = success,
                billResultJson = billResult?.let { objectMapper.writeValueAsString(it) },
                errorMessage = errorMessage,
                computedAt = Instant.now().toString()
            )
            
            rabbitTemplate.convertAndSend(
                BillingComputationJobRouting.EXCHANGE,
                BillingComputationJobRouting.BILL_COMPUTED,
                event
            )
            
            logger.info("Published BillComputationCompletedEvent for bill ${job.billId} (success=$success)")
        } catch (e: Exception) {
            logger.error("Failed to publish completion event for bill ${job.billId}: ${e.message}", e)
            // Don't rethrow - we've already processed the job
        }
    }
}
