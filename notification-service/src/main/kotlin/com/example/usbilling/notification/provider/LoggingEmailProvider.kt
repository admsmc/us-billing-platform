package com.example.usbilling.notification.provider

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Email provider that logs emails instead of sending them.
 * Used for development and testing.
 */
@Component
@ConditionalOnProperty(name = ["notification.email.provider"], havingValue = "logging", matchIfMissing = true)
class LoggingEmailProvider : EmailProvider {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun sendEmail(
        to: String,
        subject: String,
        htmlContent: String,
        textContent: String?,
    ): EmailSendResult {
        val messageId = UUID.randomUUID().toString()

        logger.info(
            """
            |========== EMAIL NOTIFICATION (DEV MODE) ==========
            |Message ID: $messageId
            |To: $to
            |Subject: $subject
            |---
            |HTML Content:
            |$htmlContent
            |---
            |Text Content:
            |${textContent ?: "(none)"}
            |==================================================
            """.trimMargin(),
        )

        return EmailSendResult(
            success = true,
            messageId = messageId,
        )
    }
}
