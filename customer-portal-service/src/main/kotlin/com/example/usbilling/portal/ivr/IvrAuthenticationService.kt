package com.example.usbilling.portal.ivr

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient

@Service
class IvrAuthenticationService(
    private val webClientBuilder: WebClient.Builder,
    @Value("\${customer-portal.customer-service-url}")
    private val customerServiceUrl: String,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val customerClient: WebClient by lazy {
        webClientBuilder.baseUrl(customerServiceUrl).build()
    }

    /**
     * Authenticate an IVR caller using account number and verification code.
     * Verification code could be:
     * - Last 4 digits of SSN
     * - Billing ZIP code
     * - Account PIN
     * - Last 4 digits of phone number
     */
    fun authenticate(accountNumber: String, verificationCode: String): AuthenticationResult {
        logger.info("Authenticating IVR caller: accountNumber=$accountNumber")

        return try {
            val response = customerClient
                .post()
                .uri("/accounts/authenticate")
                .bodyValue(
                    mapOf(
                        "accountNumber" to accountNumber,
                        "verificationCode" to verificationCode,
                    ),
                )
                .retrieve()
                .bodyToMono(Map::class.java)
                .block()

            if (response == null) {
                logger.warn("Authentication failed: No response from customer-service")
                return AuthenticationResult.failed()
            }

            val authenticated = response["authenticated"] as? Boolean ?: false
            if (!authenticated) {
                logger.warn("Authentication failed for account: $accountNumber")
                return AuthenticationResult.failed()
            }

            val customerId = response["customerId"] as String
            val utilityId = response["utilityId"] as String
            val accountId = response["accountId"] as String

            logger.info("Authentication successful for customer: $customerId")
            AuthenticationResult.success(
                customerId = customerId,
                utilityId = utilityId,
                accountId = accountId,
            )
        } catch (e: Exception) {
            logger.error("Authentication error for account: $accountNumber", e)
            AuthenticationResult.failed()
        }
    }
}

data class AuthenticationResult(
    val authenticated: Boolean,
    val customerId: String?,
    val utilityId: String?,
    val accountId: String?,
) {
    companion object {
        fun success(customerId: String, utilityId: String, accountId: String) = AuthenticationResult(
            authenticated = true,
            customerId = customerId,
            utilityId = utilityId,
            accountId = accountId,
        )

        fun failed() = AuthenticationResult(
            authenticated = false,
            customerId = null,
            utilityId = null,
            accountId = null,
        )
    }
}
