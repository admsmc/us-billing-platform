package com.example.usbilling.notification.email

import org.springframework.stereotype.Component

@Component
class EmailToCaseConverter {

    /**
     * Convert an inbound email into a case creation request.
     */
    fun convertToCase(
        email: InboundEmail,
        customerId: String,
        utilityId: String,
        accountId: String,
    ): CreateCaseFromEmailRequest {
        val caseType = determineCaseType(email)
        val caseCategory = determineCaseCategory(email)
        val priority = determinePriority(email)

        return CreateCaseFromEmailRequest(
            customerId = customerId,
            utilityId = utilityId,
            accountId = accountId,
            caseType = caseType,
            caseCategory = caseCategory,
            title = sanitizeSubject(email.subject),
            description = buildDescription(email),
            priority = priority,
            contactMethod = "EMAIL",
            contactValue = email.fromEmail,
            source = "EMAIL",
            openedBy = email.fromEmail,
        )
    }

    private fun determineCaseType(email: InboundEmail): String {
        val subject = email.subject.lowercase()
        val body = email.body.lowercase()

        return when {
            containsKeywords(subject, body, COMPLAINT_KEYWORDS) -> "COMPLAINT"
            containsKeywords(subject, body, DISPUTE_KEYWORDS) -> "DISPUTE"
            containsKeywords(subject, body, SERVICE_REQUEST_KEYWORDS) -> "SERVICE_REQUEST"
            else -> "INQUIRY"
        }
    }

    private fun determineCaseCategory(email: InboundEmail): String {
        val subject = email.subject.lowercase()
        val body = email.body.lowercase()

        return when {
            containsKeywords(subject, body, BILLING_KEYWORDS) -> "BILLING"
            containsKeywords(subject, body, PAYMENT_KEYWORDS) -> "PAYMENT"
            containsKeywords(subject, body, METER_KEYWORDS) -> "METER"
            containsKeywords(subject, body, SERVICE_KEYWORDS) -> "SERVICE"
            containsKeywords(subject, body, OUTAGE_KEYWORDS) -> "OUTAGE"
            containsKeywords(subject, body, ACCOUNT_KEYWORDS) -> "ACCOUNT"
            else -> "GENERAL"
        }
    }

    private fun determinePriority(email: InboundEmail): String {
        val subject = email.subject.lowercase()
        val body = email.body.lowercase()

        return when {
            containsKeywords(subject, body, URGENT_KEYWORDS) -> "HIGH"
            containsKeywords(subject, body, EMERGENCY_KEYWORDS) -> "HIGH"
            else -> "MEDIUM"
        }
    }

    private fun sanitizeSubject(subject: String): String {
        // Remove common email prefixes and trim
        return subject
            .replace(Regex("^(RE:|FW:|FWD:)\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
            .take(200) // Limit to 200 characters
    }

    private fun buildDescription(email: InboundEmail): String {
        val description = StringBuilder()
        description.append("Email from: ${email.fromName ?: email.fromEmail}\n")
        description.append("Received at: ${email.receivedAt}\n")
        description.append("Subject: ${email.subject}\n\n")
        description.append("Message:\n")
        description.append(email.body.take(5000)) // Limit body to 5000 characters

        if (email.attachments.isNotEmpty()) {
            description.append("\n\nAttachments (${email.attachments.size}):\n")
            email.attachments.forEach {
                description.append("- ${it.filename} (${it.contentType}, ${it.size} bytes)\n")
            }
        }

        return description.toString()
    }

    private fun containsKeywords(subject: String, body: String, keywords: List<String>): Boolean {
        val text = "$subject $body"
        return keywords.any { keyword ->
            text.contains(keyword, ignoreCase = true)
        }
    }

    companion object {
        private val COMPLAINT_KEYWORDS = listOf(
            "complaint",
            "complain",
            "unhappy",
            "dissatisfied",
            "poor service",
            "terrible",
            "awful",
        )

        private val DISPUTE_KEYWORDS = listOf(
            "dispute",
            "disagree",
            "incorrect",
            "wrong",
            "overcharged",
            "too high",
            "error",
        )

        private val SERVICE_REQUEST_KEYWORDS = listOf(
            "start service",
            "stop service",
            "move",
            "transfer",
            "new service",
            "disconnect",
            "reconnect",
        )

        private val BILLING_KEYWORDS = listOf(
            "bill",
            "invoice",
            "statement",
            "charge",
            "balance",
            "amount due",
        )

        private val PAYMENT_KEYWORDS = listOf(
            "payment",
            "pay",
            "paid",
            "autopay",
            "auto-pay",
            "payment plan",
        )

        private val METER_KEYWORDS = listOf(
            "meter",
            "reading",
            "usage",
            "consumption",
        )

        private val SERVICE_KEYWORDS = listOf(
            "service",
            "electric",
            "gas",
            "water",
            "utility",
        )

        private val OUTAGE_KEYWORDS = listOf(
            "outage",
            "power out",
            "no power",
            "no service",
            "down",
        )

        private val ACCOUNT_KEYWORDS = listOf(
            "account",
            "profile",
            "contact",
            "address",
            "name",
        )

        private val URGENT_KEYWORDS = listOf(
            "urgent",
            "asap",
            "immediately",
            "quickly",
        )

        private val EMERGENCY_KEYWORDS = listOf(
            "emergency",
            "gas leak",
            "dangerous",
            "hazard",
        )
    }
}

data class CreateCaseFromEmailRequest(
    val customerId: String,
    val utilityId: String,
    val accountId: String,
    val caseType: String,
    val caseCategory: String,
    val title: String,
    val description: String,
    val priority: String,
    val contactMethod: String,
    val contactValue: String,
    val source: String,
    val openedBy: String,
)
