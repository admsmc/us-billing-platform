package com.example.usbilling.notification.provider

/**
 * Email provider abstraction for sending email notifications.
 */
interface EmailProvider {
    
    /**
     * Send an email notification.
     * 
     * @param to Recipient email address
     * @param subject Email subject
     * @param htmlContent HTML email body
     * @param textContent Plain text email body (fallback)
     * @return true if sent successfully, false otherwise
     */
    fun sendEmail(
        to: String,
        subject: String,
        htmlContent: String,
        textContent: String? = null
    ): EmailSendResult
}

data class EmailSendResult(
    val success: Boolean,
    val messageId: String? = null,
    val errorMessage: String? = null
)
