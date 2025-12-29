package com.example.usbilling.orchestrator.jobs

import com.example.usbilling.messaging.jobs.BillingComputationJobRouting
import com.example.usbilling.messaging.jobs.ComputeBillJob
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.stereotype.Component
import java.util.*

/**
 * Publishes billing computation jobs to RabbitMQ.
 */
@Component
class BillingJobPublisher(
    private val rabbitTemplate: RabbitTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Publish a ComputeBillJob to the queue for worker processing.
     *
     * @param job The billing computation job
     */
    fun publishComputeBillJob(job: ComputeBillJob) {
        try {
            logger.info("Publishing ComputeBillJob for bill ${job.billId}")

            rabbitTemplate.convertAndSend(
                BillingComputationJobRouting.EXCHANGE,
                BillingComputationJobRouting.COMPUTE_BILL,
                job,
            )

            logger.info("Successfully published ComputeBillJob for bill ${job.billId}")
        } catch (e: Exception) {
            logger.error("Failed to publish ComputeBillJob for bill ${job.billId}: ${e.message}", e)
            throw e
        }
    }

    /**
     * Create a ComputeBillJob with a unique message ID.
     */
    fun createComputeBillJob(
        billId: String,
        utilityId: String,
        customerId: String,
        billingPeriodId: String,
        serviceState: String,
    ): ComputeBillJob = ComputeBillJob(
        messageId = UUID.randomUUID().toString(),
        billId = billId,
        utilityId = utilityId,
        customerId = customerId,
        billingPeriodId = billingPeriodId,
        serviceState = serviceState,
        attempt = 1,
    )
}
