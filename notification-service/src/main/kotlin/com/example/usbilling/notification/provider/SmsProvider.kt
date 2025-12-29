package com.example.usbilling.notification.provider

/**
 * SMS provider abstraction for sending SMS notifications.
 */
interface SmsProvider {

    /**
     * Send an SMS notification.
     *
     * @param to Recipient phone number (E.164 format: +1234567890)
     * @param message SMS message content (160 chars recommended)
     * @return true if sent successfully, false otherwise
     */
    fun sendSms(
        to: String,
        message: String,
    ): SmsSendResult
}

data class SmsSendResult(
    val success: Boolean,
    val messageId: String? = null,
    val errorMessage: String? = null,
)
