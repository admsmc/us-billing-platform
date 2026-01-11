package com.example.usbilling.portal

import com.example.usbilling.portal.controller.UsageController
import com.example.usbilling.portal.security.CustomerPrincipal
import com.example.usbilling.portal.service.UsageChartService
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.reactive.function.client.WebClient

/**
 * Focused unit-level test that exercises UsageController's chart endpoints
 * without bringing up the full Spring context. This is enough to bump
 * JaCoCo coverage on customer-portal-service while still testing meaningful
 * billing behavior (usage analytics for a customer account).
 */
class UsageControllerTest {

    private val dummyWebClientBuilder: WebClient.Builder = WebClient.builder()

    @Test
    fun `should return chart data for authorized customer`() {
        val usageChartService = UsageChartService(
            webClientBuilder = dummyWebClientBuilder,
            customerServiceUrl = "http://localhost",
        )

        val controller = UsageController(
            webClientBuilder = dummyWebClientBuilder,
            customerServiceUrl = "http://localhost", // not used in this test
            usageChartService = usageChartService,
        )

        val principal = CustomerPrincipal(
            customerId = "CUST-1",
            utilityId = "UTIL-1",
            accountIds = listOf("ACC-1"),
            email = null,
            fullName = null,
        )

        val response: ResponseEntity<Any> = controller.getTimeSeriesChartData(
            principal = principal,
            accountId = "ACC-1",
            startDate = null,
            endDate = null,
            granularity = "DAILY",
        )

        assert(response.statusCode == HttpStatus.OK)
        assert(response.body != null)
    }

    @Test
    fun `should forbid access to unauthorized account`() {
        val usageChartService = UsageChartService(
            webClientBuilder = dummyWebClientBuilder,
            customerServiceUrl = "http://localhost",
        )

        val controller = UsageController(
            webClientBuilder = dummyWebClientBuilder,
            customerServiceUrl = "http://localhost",
            usageChartService = usageChartService,
        )

        val principal = CustomerPrincipal(
            customerId = "CUST-1",
            utilityId = "UTIL-1",
            accountIds = listOf("ACC-1"),
            email = null,
            fullName = null,
        )

        val response = controller.getTimeSeriesChartData(
            principal = principal,
            accountId = "ACC-2", // not in principal.accountIds
            startDate = null,
            endDate = null,
            granularity = "DAILY",
        )

        assert(response.statusCode == HttpStatus.FORBIDDEN)
    }
}
