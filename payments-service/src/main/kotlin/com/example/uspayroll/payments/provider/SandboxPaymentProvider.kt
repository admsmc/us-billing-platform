package com.example.uspayroll.payments.provider

import com.example.uspayroll.messaging.events.payments.PaycheckPaymentLifecycleStatus
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.time.Instant

@ConfigurationProperties(prefix = "payments.provider.sandbox")
data class SandboxPaymentProviderProperties(
    /** If true, sandbox will immediately settle (or fail) submitted payments. */
    var autoSettle: Boolean = true,
    /** Test hook: if set, any payment with this net_cents will be failed instead of settled. */
    var failIfNetCentsEquals: Long? = null,
)

@Configuration
@EnableConfigurationProperties(SandboxPaymentProviderProperties::class)
class SandboxPaymentProviderConfig

/**
 * Sandbox provider used for local/dev and tests.
 *
 * Produces deterministic provider refs so submission is idempotent by construction.
 */
@Component
@ConditionalOnProperty(prefix = "payments.provider", name = ["type"], havingValue = "sandbox", matchIfMissing = true)
class SandboxPaymentProvider(
    private val props: SandboxPaymentProviderProperties,
) : PaymentProvider {
    override val providerName: String = "SANDBOX"

    override fun submitBatch(request: PaymentBatchSubmissionRequest, now: Instant): PaymentBatchSubmissionResult {
        val batchRef = "sandbox-batch:${request.employerId}:${request.batchId}"

        val results = request.payments.map { p ->
            val paymentRef = "sandbox-payment:${request.employerId}:${p.paymentId}"

            val terminal = if (!props.autoSettle) {
                null
            } else {
                val shouldFail = props.failIfNetCentsEquals != null && p.netCents == props.failIfNetCentsEquals && p.attempts == 0
                if (shouldFail) PaycheckPaymentLifecycleStatus.FAILED else PaycheckPaymentLifecycleStatus.SETTLED
            }

            PaymentSubmissionResult(
                paymentId = p.paymentId,
                providerPaymentRef = paymentRef,
                terminalStatus = terminal,
                error = if (terminal == PaycheckPaymentLifecycleStatus.FAILED) "sandbox_simulated_failure" else null,
            )
        }

        return PaymentBatchSubmissionResult(
            provider = providerName,
            providerBatchRef = batchRef,
            paymentResults = results,
        )
    }
}
