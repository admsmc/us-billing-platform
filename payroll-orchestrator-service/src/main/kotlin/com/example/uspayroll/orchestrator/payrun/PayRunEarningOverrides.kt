package com.example.uspayroll.orchestrator.payrun

import com.example.uspayroll.payroll.model.EarningCode
import com.example.uspayroll.payroll.model.EarningInput
import com.example.uspayroll.shared.Money
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

/**
 * Persisted representation of explicit per-employee earning overrides attached to a pay run item.
 *
 * These are primarily used for off-cycle runs (bonuses/commissions) where base wages are suppressed
 * and only these earnings are paid.
 */
data class PayRunEarningOverride(
    val code: String,
    val units: Double = 1.0,
    val rateCents: Long? = null,
    val amountCents: Long? = null,
)

@Component
class PayRunEarningOverridesCodec(
    private val objectMapper: ObjectMapper,
) {

    fun encode(overrides: List<PayRunEarningOverride>): String = objectMapper.writeValueAsString(overrides)

    fun decodeToEarningInputs(json: String): List<EarningInput> {
        val stored: Array<PayRunEarningOverride> = objectMapper.readValue(json, Array<PayRunEarningOverride>::class.java)
        return stored.map { it.toEarningInput() }
    }

    private fun PayRunEarningOverride.toEarningInput(): EarningInput {
        val code = EarningCode(code)
        val rate = rateCents?.let { Money(it) }
        val amount = amountCents?.let { Money(it) }
        return EarningInput(
            code = code,
            units = units,
            rate = rate,
            amount = amount,
        )
    }
}
