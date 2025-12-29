package com.example.usbilling.csrportal.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient

@Service
class QuickActionService(
    private val webClientBuilder: WebClient.Builder,
    @Value("\${csr-portal.customer-service-url}")
    private val customerServiceUrl: String,
    @Value("\${csr-portal.billing-orchestrator-url}")
    private val billingOrchestratorUrl: String,
    @Value("\${csr-portal.case-management-url}")
    private val caseManagementUrl: String,
    @Value("\${csr-portal.notification-service-url}")
    private val notificationServiceUrl: String,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val customerClient: WebClient by lazy {
        webClientBuilder.baseUrl(customerServiceUrl).build()
    }

    private val billingClient: WebClient by lazy {
        webClientBuilder.baseUrl(billingOrchestratorUrl).build()
    }

    private val caseClient: WebClient by lazy {
        webClientBuilder.baseUrl(caseManagementUrl).build()
    }

    private val notificationClient: WebClient by lazy {
        webClientBuilder.baseUrl(notificationServiceUrl).build()
    }

    /**
     * Apply a one-time credit to customer's account.
     */
    fun applyCredit(
        customerId: String,
        utilityId: String,
        amountCents: Long,
        reason: String,
        csrId: String,
    ): ActionResult {
        logger.info("CSR $csrId applying credit of $amountCents cents to customer $customerId: $reason")

        return try {
            val response = billingClient
                .post()
                .uri("/utilities/{utilityId}/customers/{customerId}/credits", utilityId, customerId)
                .bodyValue(
                    mapOf(
                        "amountCents" to amountCents,
                        "reason" to reason,
                        "appliedBy" to csrId,
                    ),
                )
                .retrieve()
                .bodyToMono(Map::class.java)
                .block()

            ActionResult.success("Credit applied successfully", response)
        } catch (e: Exception) {
            logger.error("Failed to apply credit", e)
            ActionResult.failure("Failed to apply credit: ${e.message}")
        }
    }

    /**
     * Waive a late fee for customer.
     */
    fun waiveLateFee(
        customerId: String,
        utilityId: String,
        billId: String,
        reason: String,
        csrId: String,
    ): ActionResult {
        logger.info("CSR $csrId waiving late fee for customer $customerId on bill $billId: $reason")

        return try {
            val response = billingClient
                .post()
                .uri("/utilities/{utilityId}/bills/{billId}/waive-late-fee", utilityId, billId)
                .bodyValue(
                    mapOf(
                        "reason" to reason,
                        "waivedBy" to csrId,
                    ),
                )
                .retrieve()
                .bodyToMono(Map::class.java)
                .block()

            ActionResult.success("Late fee waived successfully", response)
        } catch (e: Exception) {
            logger.error("Failed to waive late fee", e)
            ActionResult.failure("Failed to waive late fee: ${e.message}")
        }
    }

    /**
     * Send email to customer on behalf of CSR.
     */
    fun sendEmail(
        customerId: String,
        utilityId: String,
        subject: String,
        body: String,
        csrId: String,
    ): ActionResult {
        logger.info("CSR $csrId sending email to customer $customerId: $subject")

        return try {
            val response = notificationClient
                .post()
                .uri("/notifications/email/send")
                .bodyValue(
                    mapOf(
                        "customerId" to customerId,
                        "utilityId" to utilityId,
                        "subject" to subject,
                        "body" to body,
                        "sentBy" to csrId,
                    ),
                )
                .retrieve()
                .bodyToMono(Map::class.java)
                .block()

            ActionResult.success("Email sent successfully", response)
        } catch (e: Exception) {
            logger.error("Failed to send email", e)
            ActionResult.failure("Failed to send email: ${e.message}")
        }
    }

    /**
     * Create a case on behalf of customer.
     */
    fun createCase(
        customerId: String,
        utilityId: String,
        accountId: String,
        caseType: String,
        caseCategory: String,
        title: String,
        description: String,
        priority: String,
        csrId: String,
    ): ActionResult {
        logger.info("CSR $csrId creating case for customer $customerId: $title")

        return try {
            val response = caseClient
                .post()
                .uri("/utilities/{utilityId}/cases", utilityId)
                .bodyValue(
                    mapOf(
                        "customerId" to customerId,
                        "accountId" to accountId,
                        "caseType" to caseType,
                        "caseCategory" to caseCategory,
                        "title" to title,
                        "description" to description,
                        "priority" to priority,
                        "openedBy" to csrId,
                        "source" to "CSR",
                    ),
                )
                .retrieve()
                .bodyToMono(Map::class.java)
                .block()

            ActionResult.success("Case created successfully", response)
        } catch (e: Exception) {
            logger.error("Failed to create case", e)
            ActionResult.failure("Failed to create case: ${e.message}")
        }
    }

    /**
     * Extend payment due date for a bill.
     */
    fun extendDueDate(
        customerId: String,
        utilityId: String,
        billId: String,
        newDueDate: String,
        reason: String,
        csrId: String,
    ): ActionResult {
        logger.info("CSR $csrId extending due date for customer $customerId on bill $billId to $newDueDate: $reason")

        return try {
            val response = billingClient
                .put()
                .uri("/utilities/{utilityId}/bills/{billId}/due-date", utilityId, billId)
                .bodyValue(
                    mapOf(
                        "newDueDate" to newDueDate,
                        "reason" to reason,
                        "changedBy" to csrId,
                    ),
                )
                .retrieve()
                .bodyToMono(Map::class.java)
                .block()

            ActionResult.success("Due date extended successfully", response)
        } catch (e: Exception) {
            logger.error("Failed to extend due date", e)
            ActionResult.failure("Failed to extend due date: ${e.message}")
        }
    }
}

sealed class ActionResult {
    abstract val message: String
    abstract val success: Boolean

    data class Success(
        override val message: String,
        val data: Any?,
    ) : ActionResult() {
        override val success: Boolean = true
    }

    data class Failure(
        override val message: String,
    ) : ActionResult() {
        override val success: Boolean = false
    }

    companion object {
        fun success(message: String, data: Any? = null) = Success(message, data)
        fun failure(message: String) = Failure(message)
    }
}
