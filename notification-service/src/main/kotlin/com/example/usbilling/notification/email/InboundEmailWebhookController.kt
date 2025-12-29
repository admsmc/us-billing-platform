package com.example.usbilling.notification.email

import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.LocalDateTime
import java.util.*

/**
 * Webhook endpoint for receiving inbound emails from email providers (e.g., SendGrid Inbound Parse).
 */
@RestController
@RequestMapping("/webhooks/email")
class InboundEmailWebhookController(
    private val inboundEmailProcessor: InboundEmailProcessor,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * SendGrid Inbound Parse webhook endpoint.
     * See: https://docs.sendgrid.com/for-developers/parsing-email/setting-up-the-inbound-parse-webhook
     */
    @PostMapping("/sendgrid")
    fun handleSendGridInbound(
        @RequestParam("from") from: String,
        @RequestParam("to") to: String,
        @RequestParam("subject") subject: String,
        @RequestParam("text") textBody: String,
        @RequestParam("html", required = false) htmlBody: String?,
        @RequestParam("headers", required = false) headers: String?,
        @RequestHeader("Message-ID", required = false) messageId: String?,
    ): ResponseEntity<Map<String, String>> {
        logger.info("Received inbound email from SendGrid: from=$from, subject=$subject")

        // Parse sender info
        val (email, name) = parseSenderInfo(from)

        // Create InboundEmail object
        val inboundEmail = InboundEmail(
            messageId = messageId ?: UUID.randomUUID().toString(),
            fromEmail = email,
            fromName = name,
            toEmail = to,
            subject = subject,
            body = textBody,
            htmlBody = htmlBody,
            receivedAt = LocalDateTime.now(),
            headers = parseHeaders(headers),
            attachments = emptyList(), // TODO: Handle attachments if needed
        )

        // Process email
        val result = inboundEmailProcessor.processInboundEmail(inboundEmail)

        return when (result) {
            is ProcessingResult.Success -> {
                ResponseEntity.ok(
                    mapOf(
                        "status" to "success",
                        "caseNumber" to result.caseNumber,
                    ),
                )
            }
            is ProcessingResult.CustomerNotFound -> {
                logger.warn("Customer not found for email: $email")
                ResponseEntity.ok(
                    mapOf(
                        "status" to "customer_not_found",
                        "message" to "No account found for email: $email",
                    ),
                )
            }
            is ProcessingResult.Failure -> {
                logger.error("Failed to process email: ${result.errorMessage}")
                ResponseEntity.ok(
                    mapOf(
                        "status" to "failure",
                        "error" to result.errorMessage,
                    ),
                )
            }
        }
    }

    /**
     * Generic inbound email webhook for testing/development.
     */
    @PostMapping("/receive")
    fun receiveEmail(
        @RequestBody request: InboundEmailRequest,
    ): ResponseEntity<Map<String, String>> {
        logger.info("Received inbound email: from=${request.from}, subject=${request.subject}")

        val inboundEmail = InboundEmail(
            messageId = request.messageId ?: UUID.randomUUID().toString(),
            fromEmail = request.from,
            fromName = request.fromName,
            toEmail = request.to,
            subject = request.subject,
            body = request.body,
            htmlBody = request.htmlBody,
            receivedAt = request.receivedAt ?: LocalDateTime.now(),
            headers = request.headers,
            attachments = emptyList(),
        )

        val result = inboundEmailProcessor.processInboundEmail(inboundEmail)

        return when (result) {
            is ProcessingResult.Success -> {
                ResponseEntity.ok(
                    mapOf(
                        "status" to "success",
                        "caseNumber" to result.caseNumber,
                    ),
                )
            }
            is ProcessingResult.CustomerNotFound -> {
                ResponseEntity.ok(
                    mapOf(
                        "status" to "customer_not_found",
                        "message" to "No account found for email: ${request.from}",
                    ),
                )
            }
            is ProcessingResult.Failure -> {
                ResponseEntity.status(500).body(
                    mapOf(
                        "status" to "failure",
                        "error" to result.errorMessage,
                    ),
                )
            }
        }
    }

    private fun parseSenderInfo(from: String): Pair<String, String?> {
        // Parse "Name <email@example.com>" or "email@example.com"
        val namePattern = Regex("""^(.+?)\s*<([^>]+)>$""")
        val match = namePattern.find(from)

        return if (match != null) {
            val name = match.groupValues[1].trim()
            val email = match.groupValues[2].trim()
            Pair(email, name)
        } else {
            Pair(from.trim(), null)
        }
    }

    private fun parseHeaders(headersString: String?): Map<String, String> {
        if (headersString.isNullOrBlank()) return emptyMap()

        return try {
            // Simple parsing - headers are newline-separated "Key: Value" pairs
            headersString.lines()
                .filter { it.contains(":") }
                .associate {
                    val parts = it.split(":", limit = 2)
                    parts[0].trim() to parts[1].trim()
                }
        } catch (e: Exception) {
            logger.warn("Failed to parse email headers", e)
            emptyMap()
        }
    }
}

data class InboundEmailRequest(
    val messageId: String?,
    val from: String,
    val fromName: String?,
    val to: String,
    val subject: String,
    val body: String,
    val htmlBody: String?,
    val receivedAt: LocalDateTime?,
    val headers: Map<String, String> = emptyMap(),
)
