package com.example.usbilling.portal.controller

import com.example.usbilling.portal.security.CustomerPrincipal
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono

@RestController
@RequestMapping("/api/customers/me")
class AccountController(
    private val webClientBuilder: WebClient.Builder,
    @Value("\${customer-portal.customer-service-url}")
    private val customerServiceUrl: String,
) {

    private val customerClient: WebClient by lazy {
        webClientBuilder.baseUrl(customerServiceUrl).build()
    }

    @GetMapping("/accounts")
    fun listAccounts(
        @AuthenticationPrincipal principal: CustomerPrincipal,
    ): ResponseEntity<Any> {
        // Fetch all accounts for this customer
        val accounts = principal.accountIds.map { accountId ->
            val account = customerClient
                .get()
                .uri("/utilities/{utilityId}/accounts/{accountId}", principal.utilityId, accountId)
                .retrieve()
                .bodyToMono<Map<String, Any>>()
                .block()

            account
        }

        return ResponseEntity.ok(mapOf("accounts" to accounts))
    }

    @GetMapping("/profile")
    fun getProfile(
        @AuthenticationPrincipal principal: CustomerPrincipal,
    ): ResponseEntity<Any> {
        // Fetch customer profile from customer-service
        val profile = customerClient
            .get()
            .uri("/utilities/{utilityId}/customers/{customerId}", principal.utilityId, principal.customerId)
            .retrieve()
            .onStatus({ it.is4xxClientError || it.is5xxServerError }) { response ->
                response.bodyToMono<String>().map { body ->
                    RuntimeException("Failed to fetch profile: $body")
                }
            }
            .bodyToMono<Map<String, Any>>()
            .block()

        return ResponseEntity.ok(profile)
    }

    @PutMapping("/profile")
    fun updateProfile(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @RequestBody updates: Map<String, Any>,
    ): ResponseEntity<Any> {
        // Validate that updates don't include sensitive fields that shouldn't be modified
        val allowedFields = setOf("email", "phone", "mailingAddress", "notificationPreferences")
        val invalidFields = updates.keys.filter { it !in allowedFields }

        if (invalidFields.isNotEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(mapOf("error" to "Cannot update fields: ${invalidFields.joinToString()}"))
        }

        // Update customer profile in customer-service
        val updatedProfile = customerClient
            .put()
            .uri("/utilities/{utilityId}/customers/{customerId}", principal.utilityId, principal.customerId)
            .bodyValue(updates)
            .retrieve()
            .onStatus({ it.is4xxClientError || it.is5xxServerError }) { response ->
                response.bodyToMono<String>().map { body ->
                    RuntimeException("Failed to update profile: $body")
                }
            }
            .bodyToMono<Map<String, Any>>()
            .block()

        return ResponseEntity.ok(updatedProfile)
    }
}
