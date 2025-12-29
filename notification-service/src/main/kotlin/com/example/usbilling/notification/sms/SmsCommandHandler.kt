package com.example.usbilling.notification.sms

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import java.text.NumberFormat
import java.util.*

@Service
class SmsCommandHandler(
    private val webClientBuilder: WebClient.Builder,
    @Value("\${notification.customer-service-url}")
    private val customerServiceUrl: String,
    @Value("\${notification.billing-orchestrator-url}")
    private val billingOrchestratorUrl: String,
    @Value("\${notification.customer-portal-url}")
    private val customerPortalUrl: String,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val customerClient: WebClient by lazy {
        webClientBuilder.baseUrl(customerServiceUrl).build()
    }

    private val billingClient: WebClient by lazy {
        webClientBuilder.baseUrl(billingOrchestratorUrl).build()
    }

    /**
     * Handle an SMS command and return a response message.
     */
    fun handleCommand(sms: InboundSms): CommandResponse {
        logger.info("Handling SMS command from ${sms.fromNumber}: ${sms.body}")

        // Parse command (case insensitive)
        val command = sms.body.trim().uppercase()

        return when {
            command.startsWith("BAL") -> handleBalanceCommand(sms)
            command.startsWith("BILL") -> handleBillCommand(sms)
            command.startsWith("PAY") -> handlePayCommand(sms)
            command.startsWith("HELP") -> handleHelpCommand()
            command.startsWith("STOP") || command.startsWith("UNSUBSCRIBE") -> handleStopCommand()
            else -> CommandResponse.error("Unknown command. Text HELP for available commands.")
        }
    }

    private fun handleBalanceCommand(sms: InboundSms): CommandResponse {
        // Look up customer by phone number
        val customer = findCustomerByPhone(sms.fromNumber)
            ?: return CommandResponse.error("Account not found. Please register your phone number online.")

        // Get current balance
        val balance = getCurrentBalance(customer.utilityId, customer.customerId)
            ?: return CommandResponse.error("Unable to retrieve balance at this time.")

        val formattedBalance = NumberFormat.getCurrencyInstance(Locale.US).format(balance / 100.0)
        val dueDate = "12/31/2025" // TODO: Get actual due date

        return CommandResponse.success(
            "Your current balance is $formattedBalance. Due date: $dueDate. Text PAY to make a payment.",
        )
    }

    private fun handleBillCommand(sms: InboundSms): CommandResponse {
        val customer = findCustomerByPhone(sms.fromNumber)
            ?: return CommandResponse.error("Account not found. Please register your phone number online.")

        val lastBill = getLastBill(customer.utilityId, customer.customerId)
            ?: return CommandResponse.error("No recent bills found.")

        val formattedAmount = NumberFormat.getCurrencyInstance(Locale.US).format(lastBill.amountCents / 100.0)

        return CommandResponse.success(
            "Your last bill (${lastBill.billDate}) was $formattedAmount. Text BAL for current balance.",
        )
    }

    private fun handlePayCommand(sms: InboundSms): CommandResponse {
        val customer = findCustomerByPhone(sms.fromNumber)
            ?: return CommandResponse.error("Account not found. Please register your phone number online.")

        // Generate payment link (short URL)
        val paymentLink = "$customerPortalUrl/pay?c=${customer.customerId}"

        return CommandResponse.success(
            "Make a payment here: $paymentLink",
        )
    }

    private fun handleHelpCommand(): CommandResponse = CommandResponse.success(
        """
            Available commands:
            BAL - Check account balance
            BILL - View last bill
            PAY - Get payment link
            HELP - Show this help
            STOP - Unsubscribe from SMS
        """.trimIndent(),
    )

    private fun handleStopCommand(): CommandResponse {
        // TODO: Update customer's SMS preferences to opt-out
        logger.info("Customer requested SMS opt-out")
        return CommandResponse.success(
            "You have been unsubscribed from SMS notifications. Reply START to resubscribe.",
        )
    }

    private fun findCustomerByPhone(phoneNumber: String): CustomerInfo? = try {
        val response = customerClient
            .get()
            .uri("/customers/search?phone={phone}", normalizePhoneNumber(phoneNumber))
            .retrieve()
            .bodyToMono(Map::class.java)
            .block()

        response?.let {
            CustomerInfo(
                customerId = it["customerId"] as String,
                utilityId = it["utilityId"] as String,
                phoneNumber = phoneNumber,
            )
        }
    } catch (e: Exception) {
        logger.error("Failed to look up customer by phone: $phoneNumber", e)
        null
    }

    private fun getCurrentBalance(utilityId: String, customerId: String): Long? = try {
        val response = billingClient
            .get()
            .uri("/utilities/{utilityId}/customers/{customerId}/balance", utilityId, customerId)
            .retrieve()
            .bodyToMono(Map::class.java)
            .block()

        (response?.get("balanceCents") as? Number)?.toLong()
    } catch (e: Exception) {
        logger.error("Failed to get balance for customer: $customerId", e)
        null
    }

    private fun getLastBill(utilityId: String, customerId: String): BillInfo? = try {
        val response = billingClient
            .get()
            .uri("/utilities/{utilityId}/customers/{customerId}/bills?limit=1", utilityId, customerId)
            .retrieve()
            .bodyToMono(Map::class.java)
            .block()

        @Suppress("UNCHECKED_CAST")
        val bills = response?.get("bills") as? List<Map<String, Any>>
        bills?.firstOrNull()?.let { bill ->
            BillInfo(
                billId = bill["billId"] as String,
                billDate = bill["billDate"] as String,
                amountCents = (bill["totalAmountCents"] as Number).toLong(),
            )
        }
    } catch (e: Exception) {
        logger.error("Failed to get last bill for customer: $customerId", e)
        null
    }

    private fun normalizePhoneNumber(phone: String): String {
        // Remove all non-digit characters
        return phone.replace(Regex("[^0-9]"), "")
    }

    private data class CustomerInfo(
        val customerId: String,
        val utilityId: String,
        val phoneNumber: String,
    )

    private data class BillInfo(
        val billId: String,
        val billDate: String,
        val amountCents: Long,
    )
}

data class CommandResponse(
    val message: String,
    val success: Boolean,
) {
    companion object {
        fun success(message: String) = CommandResponse(message, true)
        fun error(message: String) = CommandResponse(message, false)
    }
}
