package com.example.usbilling.orchestrator.client

import com.example.usbilling.billing.model.BillResult
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.Duration

/**
 * HTTP client for billing-worker-service.
 * Triggers bill computation and receives results.
 */
@Component
class BillingWorkerClient(
    private val webClientBuilder: WebClient.Builder,
    @Value("\${services.billing-worker.url:http://localhost:8084}") private val workerServiceUrl: String,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val webClient: WebClient by lazy {
        webClientBuilder
            .baseUrl(workerServiceUrl)
            .build()
    }

    /**
     * Request bill computation from worker service.
     *
     * @param request Bill computation request
     * @return Computed bill result, or null if computation failed
     */
    fun computeBill(request: ComputeBillRequest): BillResult? = try {
        logger.info("Requesting bill computation for bill ${request.billId}")

        val result = webClient.post()
            .uri("/compute-bill")
            .bodyValue(request)
            .retrieve()
            .bodyToMono<BillResult>()
            .timeout(Duration.ofSeconds(30))
            .block()

        logger.info("Bill computation successful for bill ${request.billId}")
        result
    } catch (e: Exception) {
        logger.error("Bill computation failed for bill ${request.billId}: ${e.message}", e)
        null
    }
}

/**
 * Request to compute a bill.
 */
data class ComputeBillRequest(
    val billId: String,
    val utilityId: String,
    val customerId: String,
    val billingPeriodId: String,
    val serviceState: String,
)
