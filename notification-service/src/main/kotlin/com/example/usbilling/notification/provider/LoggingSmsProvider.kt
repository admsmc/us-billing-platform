package com.example.usbilling.notification.provider

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * SMS provider that logs messages instead of sending them.
 * Used for development and testing.
 */
@Component
@ConditionalOnProperty(name = ["notification.sms.provider"], havingValue = "logging", matchIfMissing = true)
class LoggingSmsProvider : SmsProvider {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun sendSms(to: String, message: String): SmsSendResult {
        val messageId = UUID.randomUUID().toString()

        logger.info(
            """
            |========== SMS NOTIFICATION (DEV MODE) ==========
            |Message ID: $messageId
            |To: $to
            |Message: $message
            |Length: ${message.length} chars
            |==================================================
            """.trimMargin(),
        )

        return SmsSendResult(
            success = true,
            messageId = messageId,
        )
    }
}
