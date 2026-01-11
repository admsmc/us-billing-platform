package com.example.usbilling.e2e

import io.restassured.RestAssured
import io.restassured.http.ContentType
import io.restassured.specification.RequestSpecification
import org.junit.jupiter.api.BeforeEach
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.DockerComposeContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.File

/**
 * Base class for E2E tests that interact with the full billing stack.
 *
 * Tests now use Testcontainers to spin up the Docker Compose environment defined in
 * docker-compose.billing.yml, so there is no need to manually run docker compose
 * before executing :e2e-tests:test or the Gradle build.
 *
 * Service ports (from docker-compose.billing.yml):
 * - customer-service: 8081
 * - rate-service: 8082
 * - regulatory-service: 8083
 * - billing-worker-service: 8084
 * - billing-orchestrator-service: 8085
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class BaseE2ETest {

    class BillingDockerCompose(file: File) : DockerComposeContainer<BillingDockerCompose>(file)

    companion object {
        private val composeFile = File("../docker-compose.billing.yml").canonicalFile

        @Container
        @JvmStatic
        val environment: BillingDockerCompose = BillingDockerCompose(composeFile)
            .withExposedService(
                "customer-service",
                8081,
                Wait.forHttp("/actuator/health").forStatusCode(200),
            )
            .withExposedService(
                "rate-service",
                8082,
                Wait.forHttp("/actuator/health").forStatusCode(200),
            )
            .withExposedService(
                "regulatory-service",
                8083,
                Wait.forHttp("/actuator/health").forStatusCode(200),
            )
            .withExposedService(
                "billing-worker-service",
                8084,
                Wait.forHttp("/health").forStatusCode(200),
            )
            .withExposedService(
                "billing-orchestrator-service",
                8085,
                Wait.forHttp("/actuator/health").forStatusCode(200),
            )

        const val CUSTOMER_SERVICE_BASE_URL = "http://localhost:8081"
        const val RATE_SERVICE_BASE_URL = "http://localhost:8082"
        const val REGULATORY_SERVICE_BASE_URL = "http://localhost:8083"
        const val BILLING_WORKER_BASE_URL = "http://localhost:8084"
        const val BILLING_ORCHESTRATOR_BASE_URL = "http://localhost:8085"

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            // Configure service URLs for tests
            registry.add("services.customer.base-url") { CUSTOMER_SERVICE_BASE_URL }
            registry.add("services.rate.base-url") { RATE_SERVICE_BASE_URL }
            registry.add("services.regulatory.base-url") { REGULATORY_SERVICE_BASE_URL }
            registry.add("services.billing-worker.base-url") { BILLING_WORKER_BASE_URL }
            registry.add("services.billing-orchestrator.base-url") { BILLING_ORCHESTRATOR_BASE_URL }
        }
    }

    @BeforeEach
    fun setupRestAssured() {
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails()
    }

    // Helper methods for building requests to each service

    protected fun customerService(): RequestSpecification = RestAssured.given()
        .baseUri(CUSTOMER_SERVICE_BASE_URL)
        .contentType(ContentType.JSON)
        .accept(ContentType.JSON)

    protected fun rateService(): RequestSpecification = RestAssured.given()
        .baseUri(RATE_SERVICE_BASE_URL)
        .contentType(ContentType.JSON)
        .accept(ContentType.JSON)

    protected fun regulatoryService(): RequestSpecification = RestAssured.given()
        .baseUri(REGULATORY_SERVICE_BASE_URL)
        .contentType(ContentType.JSON)
        .accept(ContentType.JSON)

    protected fun billingWorkerService(): RequestSpecification = RestAssured.given()
        .baseUri(BILLING_WORKER_BASE_URL)
        .contentType(ContentType.JSON)
        .accept(ContentType.JSON)

    protected fun billingOrchestratorService(): RequestSpecification = RestAssured.given()
        .baseUri(BILLING_ORCHESTRATOR_BASE_URL)
        .contentType(ContentType.JSON)
        .accept(ContentType.JSON)
}
