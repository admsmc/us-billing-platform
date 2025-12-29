package com.example.usbilling.csrportal.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import java.time.LocalDate

@Service
class Customer360Service(
    private val webClientBuilder: WebClient.Builder,
    @Value("\${csr-portal.customer-service-url}")
    private val customerServiceUrl: String,
    @Value("\${csr-portal.billing-orchestrator-url}")
    private val billingOrchestratorUrl: String,
    @Value("\${csr-portal.payments-service-url}")
    private val paymentsServiceUrl: String,
    @Value("\${csr-portal.case-management-url}")
    private val caseManagementUrl: String,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val customerClient: WebClient by lazy {
        webClientBuilder.baseUrl(customerServiceUrl).build()
    }

    private val billingClient: WebClient by lazy {
        webClientBuilder.baseUrl(billingOrchestratorUrl).build()
    }

    private val paymentsClient: WebClient by lazy {
        webClientBuilder.baseUrl(paymentsServiceUrl).build()
    }

    private val caseClient: WebClient by lazy {
        webClientBuilder.baseUrl(caseManagementUrl).build()
    }

    /**
     * Get comprehensive 360° view of a customer.
     */
    fun getCustomer360(customerId: String, utilityId: String): Customer360Data {
        logger.info("Fetching 360° view for customer: $customerId")

        // Fetch data from all services in parallel (or sequentially with error handling)
        val profile = fetchCustomerProfile(customerId, utilityId)
        val accounts = fetchAccounts(customerId, utilityId)
        val currentBalance = fetchCurrentBalance(customerId, utilityId)
        val bills = fetchBillHistory(customerId, utilityId, 12)
        val payments = fetchPaymentHistory(customerId, utilityId, 12)
        val cases = fetchCaseHistory(customerId, utilityId)
        val paymentPlans = fetchPaymentPlans(customerId, utilityId)
        val autoPay = fetchAutoPayStatus(customerId, utilityId)
        val disputes = fetchDisputes(customerId, utilityId)

        return Customer360Data(
            customerId = customerId,
            utilityId = utilityId,
            profile = profile,
            accounts = accounts,
            currentBalance = currentBalance,
            bills = bills,
            payments = payments,
            cases = cases,
            paymentPlans = paymentPlans,
            autoPay = autoPay,
            disputes = disputes,
        )
    }

    private fun fetchCustomerProfile(customerId: String, utilityId: String): Map<String, Any>? = try {
        customerClient
            .get()
            .uri("/utilities/{utilityId}/customers/{customerId}", utilityId, customerId)
            .retrieve()
            .bodyToMono(Map::class.java)
            .block() as? Map<String, Any>
    } catch (e: Exception) {
        logger.error("Failed to fetch customer profile", e)
        null
    }

    private fun fetchAccounts(customerId: String, utilityId: String): List<Map<String, Any>> = try {
        val response = customerClient
            .get()
            .uri("/utilities/{utilityId}/customers/{customerId}/accounts", utilityId, customerId)
            .retrieve()
            .bodyToMono(Map::class.java)
            .block()

        @Suppress("UNCHECKED_CAST")
        (response?.get("accounts") as? List<Map<String, Any>>) ?: emptyList()
    } catch (e: Exception) {
        logger.error("Failed to fetch accounts", e)
        emptyList()
    }

    private fun fetchCurrentBalance(customerId: String, utilityId: String): Map<String, Any>? = try {
        billingClient
            .get()
            .uri("/utilities/{utilityId}/customers/{customerId}/balance", utilityId, customerId)
            .retrieve()
            .bodyToMono(Map::class.java)
            .block() as? Map<String, Any>
    } catch (e: Exception) {
        logger.error("Failed to fetch current balance", e)
        null
    }

    private fun fetchBillHistory(customerId: String, utilityId: String, months: Int): List<Map<String, Any>> = try {
        val response = billingClient
            .get()
            .uri("/utilities/{utilityId}/customers/{customerId}/bills?limit={limit}", utilityId, customerId, months * 2)
            .retrieve()
            .bodyToMono(Map::class.java)
            .block()

        @Suppress("UNCHECKED_CAST")
        (response?.get("bills") as? List<Map<String, Any>>) ?: emptyList()
    } catch (e: Exception) {
        logger.error("Failed to fetch bill history", e)
        emptyList()
    }

    private fun fetchPaymentHistory(customerId: String, utilityId: String, months: Int): List<Map<String, Any>> = try {
        val endDate = LocalDate.now()
        val startDate = endDate.minusMonths(months.toLong())

        val response = paymentsClient
            .get()
            .uri(
                "/utilities/{utilityId}/customers/{customerId}/payments?startDate={startDate}&endDate={endDate}",
                utilityId,
                customerId,
                startDate,
                endDate,
            )
            .retrieve()
            .bodyToMono(Map::class.java)
            .block()

        @Suppress("UNCHECKED_CAST")
        (response?.get("payments") as? List<Map<String, Any>>) ?: emptyList()
    } catch (e: Exception) {
        logger.error("Failed to fetch payment history", e)
        emptyList()
    }

    private fun fetchCaseHistory(customerId: String, utilityId: String): List<Map<String, Any>> = try {
        val response = caseClient
            .get()
            .uri("/utilities/{utilityId}/customers/{customerId}/cases", utilityId, customerId)
            .retrieve()
            .bodyToMono(Map::class.java)
            .block()

        @Suppress("UNCHECKED_CAST")
        (response?.get("cases") as? List<Map<String, Any>>) ?: emptyList()
    } catch (e: Exception) {
        logger.error("Failed to fetch case history", e)
        emptyList()
    }

    private fun fetchPaymentPlans(customerId: String, utilityId: String): List<Map<String, Any>> = try {
        val response = customerClient
            .get()
            .uri("/utilities/{utilityId}/customers/{customerId}/payment-plans", utilityId, customerId)
            .retrieve()
            .bodyToMono(Map::class.java)
            .block()

        @Suppress("UNCHECKED_CAST")
        (response?.get("paymentPlans") as? List<Map<String, Any>>) ?: emptyList()
    } catch (e: Exception) {
        logger.error("Failed to fetch payment plans", e)
        emptyList()
    }

    private fun fetchAutoPayStatus(customerId: String, utilityId: String): Map<String, Any>? = try {
        customerClient
            .get()
            .uri("/utilities/{utilityId}/customers/{customerId}/autopay", utilityId, customerId)
            .retrieve()
            .bodyToMono(Map::class.java)
            .block() as? Map<String, Any>
    } catch (e: Exception) {
        logger.error("Failed to fetch auto-pay status", e)
        null
    }

    private fun fetchDisputes(customerId: String, utilityId: String): List<Map<String, Any>> = try {
        val response = caseClient
            .get()
            .uri("/utilities/{utilityId}/customers/{customerId}/disputes", utilityId, customerId)
            .retrieve()
            .bodyToMono(Map::class.java)
            .block()

        @Suppress("UNCHECKED_CAST")
        (response?.get("disputes") as? List<Map<String, Any>>) ?: emptyList()
    } catch (e: Exception) {
        logger.error("Failed to fetch disputes", e)
        emptyList()
    }
}

data class Customer360Data(
    val customerId: String,
    val utilityId: String,
    val profile: Map<String, Any>?,
    val accounts: List<Map<String, Any>>,
    val currentBalance: Map<String, Any>?,
    val bills: List<Map<String, Any>>,
    val payments: List<Map<String, Any>>,
    val cases: List<Map<String, Any>>,
    val paymentPlans: List<Map<String, Any>>,
    val autoPay: Map<String, Any>?,
    val disputes: List<Map<String, Any>>,
)
