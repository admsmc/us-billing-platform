package com.example.uspayroll.worker.client

import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.web.security.InternalJwtHs256
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.RequestEntity
import org.springframework.web.client.RestTemplate
import org.springframework.web.client.exchange
import java.net.URI

interface OrchestratorClient {
    fun startFinalize(employerId: EmployerId, payPeriodId: String, employeeIds: List<String>, requestedPayRunId: String? = null, idempotencyKey: String? = null): StartFinalizeResponse

    fun execute(
        employerId: EmployerId,
        payRunId: String,
        batchSize: Int = 25,
        maxItems: Int = 200,
        maxMillis: Long = 2_000L,
        requeueStaleMillis: Long = 10 * 60 * 1000L,
        leaseOwner: String = "worker",
        parallelism: Int = 4,
    ): Map<String, Any?>

    fun finalizeEmployeeItem(employerId: EmployerId, payRunId: String, employeeId: String): FinalizeEmployeeItemResponse

    fun getStatus(employerId: EmployerId, payRunId: String, failureLimit: Int = 25): PayRunStatusResponse
}

@ConfigurationProperties(prefix = "orchestrator")
data class OrchestratorClientProperties(
    var baseUrl: String = "http://localhost:8085",

    /** Shared-secret token sent to orchestrator internal endpoints (legacy / fallback). */
    var internalToken: String = "",

    /** Header name for internal auth token. */
    var internalTokenHeader: String = "X-Internal-Token",

    /**
     * Preferred internal auth mechanism: short-lived internal JWT (HS256).
     *
     * When set, the worker will send:
     * - Authorization: Bearer <internal-jwt>
     */
    var internalJwtSecret: String = "",

    /** Expected issuer configured on the orchestrator verifier. */
    var internalJwtIssuer: String = "us-payroll-platform",

    /** Expected audience configured on the orchestrator verifier. */
    var internalJwtAudience: String = "payroll-orchestrator-service",

    /** Subject claim identifying the caller service. */
    var internalJwtSubject: String = "payroll-worker-service",

    /** Token TTL (seconds). Keep this short in production. */
    var internalJwtTtlSeconds: Long = 60,
)

@Configuration
@EnableConfigurationProperties(OrchestratorClientProperties::class)
class OrchestratorClientConfig {

    @Bean
    fun httpOrchestratorClient(props: OrchestratorClientProperties, restTemplate: RestTemplate): OrchestratorClient = HttpOrchestratorClient(props, restTemplate)
}

class HttpOrchestratorClient(
    private val props: OrchestratorClientProperties,
    private val restTemplate: RestTemplate,
) : OrchestratorClient {

    private fun internalAuthHeaders(): HttpHeaders {
        val headers = HttpHeaders()

        if (props.internalJwtSecret.isNotBlank()) {
            val token = InternalJwtHs256.issue(
                secret = props.internalJwtSecret,
                issuer = props.internalJwtIssuer,
                subject = props.internalJwtSubject,
                audience = props.internalJwtAudience,
                ttlSeconds = props.internalJwtTtlSeconds,
            )
            headers.setBearerAuth(token)
            return headers
        }

        headers.set(props.internalTokenHeader, props.internalToken)
        return headers
    }

    override fun startFinalize(employerId: EmployerId, payPeriodId: String, employeeIds: List<String>, requestedPayRunId: String?, idempotencyKey: String?): StartFinalizeResponse {
        val url = "${props.baseUrl}/employers/${employerId.value}/payruns/finalize"
        val request = StartFinalizeRequest(
            payPeriodId = payPeriodId,
            employeeIds = employeeIds,
            requestedPayRunId = requestedPayRunId,
            idempotencyKey = idempotencyKey,
        )

        val response = restTemplate.exchange<StartFinalizeResponse>(
            RequestEntity.post(URI.create(url)).body(request),
        )

        return response.body ?: error("Orchestrator startFinalize returned null body")
    }

    override fun execute(employerId: EmployerId, payRunId: String, batchSize: Int, maxItems: Int, maxMillis: Long, requeueStaleMillis: Long, leaseOwner: String, parallelism: Int): Map<String, Any?> {
        val url = "${props.baseUrl}/employers/${employerId.value}/payruns/internal/$payRunId/execute?batchSize=$batchSize&maxItems=$maxItems&maxMillis=$maxMillis&requeueStaleMillis=$requeueStaleMillis&leaseOwner=$leaseOwner&parallelism=$parallelism"

        val headers = internalAuthHeaders()

        val response = restTemplate.exchange<Map<*, *>>(
            RequestEntity.post(URI.create(url))
                .headers(headers)
                .build(),
        )

        @Suppress("UNCHECKED_CAST")
        return response.body as? Map<String, Any?>
            ?: error("Orchestrator execute returned null body")
    }

    override fun finalizeEmployeeItem(employerId: EmployerId, payRunId: String, employeeId: String): FinalizeEmployeeItemResponse {
        val url = "${props.baseUrl}/employers/${employerId.value}/payruns/internal/$payRunId/items/$employeeId/finalize"

        val headers = internalAuthHeaders()

        val response = restTemplate.exchange<FinalizeEmployeeItemResponse>(
            RequestEntity.post(URI.create(url))
                .headers(headers)
                .build(),
        )

        return response.body ?: error("Orchestrator finalizeEmployeeItem returned null body")
    }

    override fun getStatus(employerId: EmployerId, payRunId: String, failureLimit: Int): PayRunStatusResponse {
        val url = "${props.baseUrl}/employers/${employerId.value}/payruns/$payRunId?failureLimit=$failureLimit"
        return restTemplate.getForObject(url, PayRunStatusResponse::class.java)
            ?: error("Orchestrator getStatus returned null body")
    }
}

data class StartFinalizeRequest(
    val payPeriodId: String,
    val employeeIds: List<String>,
    val requestedPayRunId: String? = null,
    val idempotencyKey: String? = null,
)

data class StartFinalizeResponse(
    val employerId: String,
    val payRunId: String,
    val status: String,
    val totalItems: Int,
    val created: Boolean,
)

data class FinalizeEmployeeItemResponse(
    val employerId: String,
    val payRunId: String,
    val employeeId: String,
    val itemStatus: String,
    val attemptCount: Int,
    val paycheckId: String? = null,
    val retryable: Boolean,
    val error: String? = null,
)

data class PayRunStatusResponse(
    val employerId: String,
    val payRunId: String,
    val payPeriodId: String,
    val status: String,
    val approvalStatus: String? = null,
    val paymentStatus: String? = null,
    val counts: Counts,
    val failures: List<FailureItem> = emptyList(),
) {
    data class Counts(
        val total: Int,
        val queued: Int,
        val running: Int,
        val succeeded: Int,
        val failed: Int,
    )

    data class FailureItem(val employeeId: String, val error: String?)
}
