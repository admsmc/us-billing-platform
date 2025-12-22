package com.example.uspayroll.worker.client

import com.example.uspayroll.payroll.model.PaycheckResult
import com.example.uspayroll.payroll.model.audit.PaycheckAudit
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.web.client.DownstreamHttpClientProperties
import com.example.uspayroll.web.client.HttpClientGuardrails
import com.example.uspayroll.web.client.RestTemplateRetryClassifier
import com.example.uspayroll.web.security.InternalJwtHs256
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.RequestEntity
import org.springframework.web.client.RestTemplate
import org.springframework.web.client.exchange
import java.net.URI
import java.time.Duration

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

    fun completeEmployeeItem(employerId: EmployerId, payRunId: String, employeeId: String, request: CompleteEmployeeItemRequest): CompleteEmployeeItemResponse

    fun getStatus(employerId: EmployerId, payRunId: String, failureLimit: Int = 25): PayRunStatusResponse
}

@ConfigurationProperties(prefix = "downstreams.orchestrator")
class OrchestratorClientProperties : DownstreamHttpClientProperties() {

    /**
     * Internal auth mechanism for orchestrator internal endpoints: short-lived internal JWT (HS256).
     *
     * The worker sends:
     * - Authorization: Bearer <internal-jwt>
     */
    var internalJwtSecret: String = ""

    /** Expected issuer configured on the orchestrator verifier. */
    var internalJwtIssuer: String = "us-payroll-platform"

    /** Expected audience configured on the orchestrator verifier. */
    var internalJwtAudience: String = "payroll-orchestrator-service"

    /** Subject claim identifying the caller service. */
    var internalJwtSubject: String = "payroll-worker-service"

    /** Key id (kid) for the internal JWT signing key. Must match orchestrator's configured keyring kid. */
    var internalJwtKid: String = "k1"

    /** Token TTL (seconds). Keep this short in production. */
    var internalJwtTtlSeconds: Long = 60

    init {
        baseUrl = "http://localhost:8085"
        connectTimeout = Duration.ofSeconds(1)
        maxRetries = 1
    }
}

@Configuration
@EnableConfigurationProperties(OrchestratorClientProperties::class)
class OrchestratorClientConfig {

    @Bean
    fun httpOrchestratorClient(props: OrchestratorClientProperties, @Qualifier("orchestratorRestTemplate") orchestratorRestTemplate: RestTemplate, meterRegistry: MeterRegistry): OrchestratorClient = HttpOrchestratorClient(props, orchestratorRestTemplate, meterRegistry)
}

class HttpOrchestratorClient(
    private val props: OrchestratorClientProperties,
    private val restTemplate: RestTemplate,
    private val meterRegistry: MeterRegistry? = null,
) : OrchestratorClient {

    private val logger = LoggerFactory.getLogger(HttpOrchestratorClient::class.java)

    private val guardrails = HttpClientGuardrails.with(
        maxRetries = props.maxRetries,
        initialBackoff = props.retryInitialBackoff,
        maxBackoff = props.retryMaxBackoff,
        backoffMultiplier = props.retryBackoffMultiplier,
        circuitBreakerPolicy = if (props.circuitBreakerEnabled) props.circuitBreaker else null,
    )

    private fun internalAuthHeaders(): HttpHeaders {
        require(props.internalJwtSecret.isNotBlank()) { "downstreams.orchestrator.internal-jwt-secret must be set" }

        val token = InternalJwtHs256.issue(
            secret = props.internalJwtSecret,
            issuer = props.internalJwtIssuer,
            subject = props.internalJwtSubject,
            audience = props.internalJwtAudience,
            ttlSeconds = props.internalJwtTtlSeconds,
            kid = props.internalJwtKid,
        )

        return HttpHeaders().apply { setBearerAuth(token) }
    }

    override fun startFinalize(employerId: EmployerId, payPeriodId: String, employeeIds: List<String>, requestedPayRunId: String?, idempotencyKey: String?): StartFinalizeResponse {
        val url = "${props.baseUrl}/employers/${employerId.value}/payruns/finalize"
        val request = StartFinalizeRequest(
            payPeriodId = payPeriodId,
            employeeIds = employeeIds,
            requestedPayRunId = requestedPayRunId,
            idempotencyKey = idempotencyKey,
        )

        val response = guardrails.execute(
            isRetryable = RestTemplateRetryClassifier::isRetryable,
            onRetry = { attempt ->
                meterRegistry
                    ?.counter(
                        "uspayroll.http.client.retries",
                        "client",
                        "orchestrator",
                        "operation",
                        "startFinalize",
                    )
                    ?.increment()

                logger.warn(
                    "http.client.retry client=orchestrator op=startFinalize attempt={}/{} delayMs={} url={} error={}",
                    attempt.attempt,
                    attempt.maxAttempts,
                    attempt.nextDelay.toMillis(),
                    url,
                    attempt.throwable.message,
                )
            },
        ) {
            restTemplate.exchange<StartFinalizeResponse>(
                RequestEntity.post(URI.create(url)).body(request),
            )
        }

        return response.body ?: error("Orchestrator startFinalize returned null body")
    }

    override fun execute(employerId: EmployerId, payRunId: String, batchSize: Int, maxItems: Int, maxMillis: Long, requeueStaleMillis: Long, leaseOwner: String, parallelism: Int): Map<String, Any?> {
        val url = "${props.baseUrl}/employers/${employerId.value}/payruns/internal/$payRunId/execute?batchSize=$batchSize&maxItems=$maxItems&maxMillis=$maxMillis&requeueStaleMillis=$requeueStaleMillis&leaseOwner=$leaseOwner&parallelism=$parallelism"

        val headers = internalAuthHeaders()

        val response = guardrails.execute(
            isRetryable = RestTemplateRetryClassifier::isRetryable,
            onRetry = { attempt ->
                meterRegistry
                    ?.counter(
                        "uspayroll.http.client.retries",
                        "client",
                        "orchestrator",
                        "operation",
                        "execute",
                    )
                    ?.increment()

                logger.warn(
                    "http.client.retry client=orchestrator op=execute attempt={}/{} delayMs={} url={} error={}",
                    attempt.attempt,
                    attempt.maxAttempts,
                    attempt.nextDelay.toMillis(),
                    url,
                    attempt.throwable.message,
                )
            },
        ) {
            restTemplate.exchange<Map<*, *>>(
                RequestEntity.post(URI.create(url))
                    .headers(headers)
                    .build(),
            )
        }

        @Suppress("UNCHECKED_CAST")
        return response.body as? Map<String, Any?>
            ?: error("Orchestrator execute returned null body")
    }

    override fun completeEmployeeItem(employerId: EmployerId, payRunId: String, employeeId: String, request: CompleteEmployeeItemRequest): CompleteEmployeeItemResponse {
        val url = "${props.baseUrl}/employers/${employerId.value}/payruns/internal/$payRunId/items/$employeeId/complete"

        val headers = internalAuthHeaders()

        val response = guardrails.execute(
            isRetryable = RestTemplateRetryClassifier::isRetryable,
            onRetry = { attempt ->
                meterRegistry
                    ?.counter(
                        "uspayroll.http.client.retries",
                        "client",
                        "orchestrator",
                        "operation",
                        "completeEmployeeItem",
                    )
                    ?.increment()

                logger.warn(
                    "http.client.retry client=orchestrator op=completeEmployeeItem attempt={}/{} delayMs={} url={} error={}",
                    attempt.attempt,
                    attempt.maxAttempts,
                    attempt.nextDelay.toMillis(),
                    url,
                    attempt.throwable.message,
                )
            },
        ) {
            restTemplate.exchange<CompleteEmployeeItemResponse>(
                RequestEntity.post(URI.create(url))
                    .headers(headers)
                    .body(request),
            )
        }

        return response.body ?: error("Orchestrator completeEmployeeItem returned null body")
    }

    override fun getStatus(employerId: EmployerId, payRunId: String, failureLimit: Int): PayRunStatusResponse {
        val url = "${props.baseUrl}/employers/${employerId.value}/payruns/$payRunId?failureLimit=$failureLimit"
        val result = guardrails.execute(
            isRetryable = RestTemplateRetryClassifier::isRetryable,
            onRetry = { attempt ->
                meterRegistry
                    ?.counter(
                        "uspayroll.http.client.retries",
                        "client",
                        "orchestrator",
                        "operation",
                        "getStatus",
                    )
                    ?.increment()

                logger.warn(
                    "http.client.retry client=orchestrator op=getStatus attempt={}/{} delayMs={} url={} error={}",
                    attempt.attempt,
                    attempt.maxAttempts,
                    attempt.nextDelay.toMillis(),
                    url,
                    attempt.throwable.message,
                )
            },
        ) {
            restTemplate.getForObject(url, PayRunStatusResponse::class.java)
        }

        return result
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

data class CompleteEmployeeItemRequest(
    val paycheckId: String,
    val paycheck: PaycheckResult? = null,
    val audit: PaycheckAudit? = null,
    val error: String? = null,
)

data class CompleteEmployeeItemResponse(
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
