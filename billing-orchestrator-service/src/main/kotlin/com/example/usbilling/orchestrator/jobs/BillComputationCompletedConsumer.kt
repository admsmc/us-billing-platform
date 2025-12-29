package com.example.usbilling.orchestrator.jobs

import com.example.usbilling.billing.model.BillResult
import com.example.usbilling.messaging.jobs.BillComputationCompletedEvent
import com.example.usbilling.messaging.jobs.BillingComputationJobRouting
import com.example.usbilling.orchestrator.service.BillingOrchestrationService
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.stereotype.Component

/**
 * Consumes bill computation completed events from worker and finalizes bills.
 */
@Component
class BillComputationCompletedConsumer(
    private val billingOrchestrationService: BillingOrchestrationService,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Consume BillComputationCompletedEvent messages and finalize bills.
     */
    @RabbitListener(queues = [BillingComputationJobRouting.BILL_COMPUTED])
    fun handleBillComputationCompleted(event: BillComputationCompletedEvent) {
        logger.info("Received BillComputationCompletedEvent for bill ${event.billId} (success=${event.success})")

        try {
            if (event.success && event.billResultJson != null) {
                // Deserialize BillResult
                val billResult = objectMapper.readValue(event.billResultJson, BillResult::class.java)

                // Finalize bill with the computed result
                val finalized = billingOrchestrationService.finalizeBill(event.billId, billResult)

                if (finalized != null) {
                    logger.info("Successfully finalized bill ${event.billId}")
                } else {
                    logger.error("Failed to finalize bill ${event.billId} - bill not found")
                }
            } else {
                // Computation failed - mark bill as FAILED
                billingOrchestrationService.updateBillStatus(event.billId, "FAILED")
                logger.error("Bill computation failed for bill ${event.billId}: ${event.errorMessage}")
            }
        } catch (e: Exception) {
            logger.error("Error handling BillComputationCompletedEvent for bill ${event.billId}: ${e.message}", e)
            // Mark bill as FAILED
            try {
                billingOrchestrationService.updateBillStatus(event.billId, "FAILED")
            } catch (ex: Exception) {
                logger.error("Failed to update bill status to FAILED for bill ${event.billId}", ex)
            }
            // Rethrow to trigger RabbitMQ retry/DLQ
            throw e
        }
    }
}
