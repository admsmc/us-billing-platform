package com.example.usbilling.portal.ivr

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.reactive.function.client.WebClient
import java.text.NumberFormat
import java.util.*

/**
 * IVR (Interactive Voice Response) API endpoints for Twilio Studio integration.
 * These endpoints are called from IVR flows to provide account information via phone.
 */
@RestController
@RequestMapping("/api/ivr")
class IvrController(
    private val ivrAuthenticationService: IvrAuthenticationService,
    private val webClientBuilder: WebClient.Builder,
    @Value("\${customer-portal.customer-service-url}")
    private val customerServiceUrl: String,
    @Value("\${customer-portal.billing-orchestrator-url}")
    private val billingOrchestratorUrl: String,
) {

    private val customerClient: WebClient by lazy {
        webClientBuilder.baseUrl(customerServiceUrl).build()
    }

    private val billingClient: WebClient by lazy {
        webClientBuilder.baseUrl(billingOrchestratorUrl).build()
    }

    /**
     * Balance inquiry via IVR.
     * Called from IVR flow after customer authentication.
     */
    @PostMapping("/balance")
    fun getBalance(
        @RequestBody request: IvrBalanceRequest,
    ): ResponseEntity<IvrBalanceResponse> {
        // Authenticate caller
        val authResult = ivrAuthenticationService.authenticate(
            accountNumber = request.accountNumber,
            verificationCode = request.verificationCode,
        )

        if (!authResult.authenticated) {
            return ResponseEntity.ok(
                IvrBalanceResponse(
                    success = false,
                    errorMessage = "Authentication failed. Please check your account number and verification code.",
                    balanceCents = null,
                    balanceText = null,
                    dueDate = null,
                ),
            )
        }

        // Get balance
        val balance = try {
            billingClient
                .get()
                .uri(
                    "/utilities/{utilityId}/customers/{customerId}/balance",
                    authResult.utilityId,
                    authResult.customerId,
                )
                .retrieve()
                .bodyToMono(Map::class.java)
                .block()
        } catch (e: Exception) {
            return ResponseEntity.ok(
                IvrBalanceResponse(
                    success = false,
                    errorMessage = "Unable to retrieve balance at this time.",
                    balanceCents = null,
                    balanceText = null,
                    dueDate = null,
                ),
            )
        }

        val balanceCents = (balance?.get("balanceCents") as? Number)?.toLong() ?: 0L
        val dueDate = balance?.get("dueDate") as? String

        val formattedBalance = NumberFormat.getCurrencyInstance(Locale.US).format(balanceCents / 100.0)
        val balanceText = formatBalanceForSpeech(balanceCents)

        return ResponseEntity.ok(
            IvrBalanceResponse(
                success = true,
                errorMessage = null,
                balanceCents = balanceCents,
                balanceText = balanceText,
                dueDate = dueDate,
            ),
        )
    }

    /**
     * Last bill inquiry via IVR.
     */
    @PostMapping("/last-bill")
    fun getLastBill(
        @RequestBody request: IvrBillRequest,
    ): ResponseEntity<IvrBillResponse> {
        // Authenticate caller
        val authResult = ivrAuthenticationService.authenticate(
            accountNumber = request.accountNumber,
            verificationCode = request.verificationCode,
        )

        if (!authResult.authenticated) {
            return ResponseEntity.ok(
                IvrBillResponse(
                    success = false,
                    errorMessage = "Authentication failed.",
                    billAmountCents = null,
                    billAmountText = null,
                    billDate = null,
                ),
            )
        }

        // Get last bill
        val billsResponse = try {
            billingClient
                .get()
                .uri(
                    "/utilities/{utilityId}/customers/{customerId}/bills?limit=1",
                    authResult.utilityId,
                    authResult.customerId,
                )
                .retrieve()
                .bodyToMono(Map::class.java)
                .block()
        } catch (e: Exception) {
            return ResponseEntity.ok(
                IvrBillResponse(
                    success = false,
                    errorMessage = "Unable to retrieve bill information.",
                    billAmountCents = null,
                    billAmountText = null,
                    billDate = null,
                ),
            )
        }

        @Suppress("UNCHECKED_CAST")
        val bills = billsResponse?.get("bills") as? List<Map<String, Any>>
        val lastBill = bills?.firstOrNull()

        if (lastBill == null) {
            return ResponseEntity.ok(
                IvrBillResponse(
                    success = false,
                    errorMessage = "No recent bills found.",
                    billAmountCents = null,
                    billAmountText = null,
                    billDate = null,
                ),
            )
        }

        val amountCents = (lastBill["totalAmountCents"] as? Number)?.toLong() ?: 0L
        val billDate = lastBill["billDate"] as? String

        return ResponseEntity.ok(
            IvrBillResponse(
                success = true,
                errorMessage = null,
                billAmountCents = amountCents,
                billAmountText = formatAmountForSpeech(amountCents),
                billDate = billDate,
            ),
        )
    }

    /**
     * Report outage via IVR.
     */
    @PostMapping("/outage")
    fun reportOutage(
        @RequestBody request: IvrOutageRequest,
    ): ResponseEntity<IvrOutageResponse> {
        // Authenticate caller
        val authResult = ivrAuthenticationService.authenticate(
            accountNumber = request.accountNumber,
            verificationCode = request.verificationCode,
        )

        if (!authResult.authenticated) {
            return ResponseEntity.ok(
                IvrOutageResponse(
                    success = false,
                    errorMessage = "Authentication failed.",
                    confirmationNumber = null,
                ),
            )
        }

        // TODO: Integrate with outage-service to report outage
        val confirmationNumber = "OUT-${System.currentTimeMillis()}"

        return ResponseEntity.ok(
            IvrOutageResponse(
                success = true,
                errorMessage = null,
                confirmationNumber = confirmationNumber,
            ),
        )
    }

    /**
     * Format balance for text-to-speech (TTS).
     * E.g., 12345 cents -> "one hundred twenty three dollars and forty five cents"
     */
    private fun formatBalanceForSpeech(balanceCents: Long): String {
        val dollars = balanceCents / 100
        val cents = balanceCents % 100

        return when {
            cents == 0L -> "$dollars dollars"
            else -> "$dollars dollars and $cents cents"
        }
    }

    /**
     * Format amount for TTS.
     */
    private fun formatAmountForSpeech(amountCents: Long): String = formatBalanceForSpeech(amountCents)
}

// Request DTOs

data class IvrBalanceRequest(
    val accountNumber: String,
    val verificationCode: String,
)

data class IvrBillRequest(
    val accountNumber: String,
    val verificationCode: String,
)

data class IvrOutageRequest(
    val accountNumber: String,
    val verificationCode: String,
    val serviceType: String?, // ELECTRIC, GAS, WATER
)

// Response DTOs

data class IvrBalanceResponse(
    val success: Boolean,
    val errorMessage: String?,
    val balanceCents: Long?,
    val balanceText: String?, // Formatted for text-to-speech
    val dueDate: String?,
)

data class IvrBillResponse(
    val success: Boolean,
    val errorMessage: String?,
    val billAmountCents: Long?,
    val billAmountText: String?, // Formatted for text-to-speech
    val billDate: String?,
)

data class IvrOutageResponse(
    val success: Boolean,
    val errorMessage: String?,
    val confirmationNumber: String?,
)
