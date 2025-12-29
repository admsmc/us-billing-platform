package com.example.usbilling.notification.email

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import java.time.LocalDateTime

@Service
class InboundEmailProcessor(
    private val emailToCaseConverter: EmailToCaseConverter,
    private val webClientBuilder: WebClient.Builder,
    @Value("\${notification.case-management-url}")
    private val caseManagementUrl: String,
    @Value("\${notification.customer-service-url}")
    private val customerServiceUrl: String,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val caseClient: WebClient by lazy {
        webClientBuilder.baseUrl(caseManagementUrl).build()
    }

    private val customerClient: WebClient by lazy {
        webClientBuilder.baseUrl(customerServiceUrl).build()
    }

    /**
     * Process an inbound email and create a case.
     */
    fun processInboundEmail(email: InboundEmail): ProcessingResult {
        logger.info("Processing inbound email from ${email.fromEmail} with subject: ${email.subject}")

        // 1. Look up customer by email
        val customer = findCustomerByEmail(email.fromEmail)
        if (customer == null) {
            logger.warn("Customer not found for email: ${email.fromEmail}")
            return ProcessingResult.customerNotFound(email)
        }

        // 2. Convert email to case
        val caseRequest = emailToCaseConverter.convertToCase(
            email = email,
            customerId = customer.customerId,
            utilityId = customer.utilityId,
            accountId = customer.primaryAccountId,
        )

        // 3. Create case
        val caseResponse = try {
            caseClient
                .post()
                .uri("/utilities/{utilityId}/cases", customer.utilityId)
                .bodyValue(caseRequest)
                .retrieve()
                .bodyToMono(Map::class.java)
                .block()
        } catch (e: Exception) {
            logger.error("Failed to create case from email", e)
            return ProcessingResult.failure(email, e.message ?: "Unknown error")
        }

        val caseNumber = caseResponse?.get("caseNumber") as? String ?: "UNKNOWN"

        // 4. Send auto-response
        sendAutoResponse(
            toEmail = email.fromEmail,
            caseNumber = caseNumber,
            utilityId = customer.utilityId,
        )

        logger.info("Successfully created case $caseNumber from email")
        return ProcessingResult.success(email, caseNumber)
    }

    private fun findCustomerByEmail(email: String): CustomerInfo? = try {
        val response = customerClient
            .get()
            .uri("/customers/search?email={email}", email)
            .retrieve()
            .bodyToMono(Map::class.java)
            .block()

        response?.let {
            CustomerInfo(
                customerId = it["customerId"] as String,
                utilityId = it["utilityId"] as String,
                primaryAccountId = it["primaryAccountId"] as? String ?: "",
                email = email,
            )
        }
    } catch (e: Exception) {
        logger.error("Failed to look up customer by email: $email", e)
        null
    }

    private fun sendAutoResponse(toEmail: String, caseNumber: String, utilityId: String) {
        // TODO: Send auto-response email with case number
        logger.info("Would send auto-response to $toEmail with case number: $caseNumber")
    }
}

data class InboundEmail(
    val messageId: String,
    val fromEmail: String,
    val fromName: String?,
    val toEmail: String,
    val subject: String,
    val body: String,
    val htmlBody: String?,
    val receivedAt: LocalDateTime,
    val headers: Map<String, String> = emptyMap(),
    val attachments: List<EmailAttachment> = emptyList(),
)

data class EmailAttachment(
    val filename: String,
    val contentType: String,
    val size: Int,
    val content: ByteArray,
)

data class CustomerInfo(
    val customerId: String,
    val utilityId: String,
    val primaryAccountId: String,
    val email: String,
)

sealed class ProcessingResult {
    abstract val email: InboundEmail
    abstract val timestamp: LocalDateTime

    data class Success(
        override val email: InboundEmail,
        val caseNumber: String,
        override val timestamp: LocalDateTime = LocalDateTime.now(),
    ) : ProcessingResult()

    data class CustomerNotFound(
        override val email: InboundEmail,
        override val timestamp: LocalDateTime = LocalDateTime.now(),
    ) : ProcessingResult()

    data class Failure(
        override val email: InboundEmail,
        val errorMessage: String,
        override val timestamp: LocalDateTime = LocalDateTime.now(),
    ) : ProcessingResult()

    companion object {
        fun success(email: InboundEmail, caseNumber: String) = Success(email, caseNumber)
        fun customerNotFound(email: InboundEmail) = CustomerNotFound(email)
        fun failure(email: InboundEmail, errorMessage: String) = Failure(email, errorMessage)
    }
}
