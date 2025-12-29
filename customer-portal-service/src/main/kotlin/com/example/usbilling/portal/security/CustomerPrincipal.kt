package com.example.usbilling.portal.security

import java.security.Principal

/**
 * Represents an authenticated customer in the portal.
 *
 * This principal is extracted from a validated JWT token and contains
 * the customer's identity and tenant (utility) context.
 */
data class CustomerPrincipal(
    val customerId: String,
    val utilityId: String,
    val accountIds: List<String>,
    val email: String?,
    val fullName: String?,
) : Principal {

    override fun getName(): String = customerId

    /**
     * Check if this customer has access to a specific account.
     */
    fun hasAccessToAccount(accountId: String): Boolean = accountIds.contains(accountId)
}
