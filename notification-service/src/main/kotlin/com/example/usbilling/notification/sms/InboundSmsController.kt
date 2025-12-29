package com.example.usbilling.notification.sms

import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Webhook endpoint for receiving inbound SMS from Twilio.
 * See: https://www.twilio.com/docs/sms/twiml
 */
@RestController
@RequestMapping("/webhooks/sms")
class InboundSmsController(
    private val smsCommandHandler: SmsCommandHandler,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Twilio SMS webhook endpoint.
     * Returns TwiML response to send back to customer.
     */
    @PostMapping("/twilio", produces = [MediaType.APPLICATION_XML_VALUE])
    fun handleTwilioInbound(
        @RequestParam("From") from: String,
        @RequestParam("To") to: String,
        @RequestParam("Body") body: String,
        @RequestParam("MessageSid", required = false) messageSid: String?,
    ): ResponseEntity<String> {
        logger.info("Received inbound SMS from Twilio: from=$from, body=$body")

        val inboundSms = InboundSms(
            messageId = messageSid ?: "",
            fromNumber = from,
            toNumber = to,
            body = body.trim(),
        )

        val response = smsCommandHandler.handleCommand(inboundSms)

        // Return TwiML response
        val twiml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Response>
                <Message>${escapeXml(response.message)}</Message>
            </Response>
        """.trimIndent()

        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_XML)
            .body(twiml)
    }

    /**
     * Generic SMS webhook for testing/development.
     */
    @PostMapping("/receive")
    fun receiveSms(
        @RequestBody request: InboundSmsRequest,
    ): ResponseEntity<SmsResponse> {
        logger.info("Received inbound SMS: from=${request.from}, body=${request.body}")

        val inboundSms = InboundSms(
            messageId = request.messageId ?: "",
            fromNumber = request.from,
            toNumber = request.to,
            body = request.body.trim(),
        )

        val response = smsCommandHandler.handleCommand(inboundSms)

        return ResponseEntity.ok(
            SmsResponse(
                status = "success",
                message = response.message,
            ),
        )
    }

    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}

data class InboundSms(
    val messageId: String,
    val fromNumber: String,
    val toNumber: String,
    val body: String,
)

data class InboundSmsRequest(
    val messageId: String?,
    val from: String,
    val to: String,
    val body: String,
)

data class SmsResponse(
    val status: String,
    val message: String,
)
