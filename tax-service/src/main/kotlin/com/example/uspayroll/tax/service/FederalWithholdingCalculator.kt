package com.example.uspayroll.tax.service

import com.example.uspayroll.payroll.engine.BasisBuilder
import com.example.uspayroll.payroll.engine.BasisContext
import com.example.uspayroll.payroll.engine.EarningsCalculator
import com.example.uspayroll.payroll.engine.pub15t.FederalWithholdingEngine
import com.example.uspayroll.payroll.engine.pub15t.WithholdingProfiles
import com.example.uspayroll.payroll.model.PaycheckInput
import com.example.uspayroll.shared.Money

/**
 * Input for computing federal income tax withholding for a single paycheck.
 *
 * This adapter allows tax-service to apply W-4 and nonresident alien
 * adjustments (per IRS Pub. 15-T) before delegating to the generic tax engine.
 */
data class FederalWithholdingInput(
    val paycheckInput: PaycheckInput,
)

/**
 * Service that computes federal income tax withholding for a paycheck, using
 * the core tax engine and additional W-4/nonresident-alien context.
 */
interface FederalWithholdingCalculator {
    fun computeWithholding(input: FederalWithholdingInput): Money
}

/**
 * Default implementation that delegates to the pure
 * [FederalWithholdingEngine] in payroll-domain using the employee snapshot,
 * earnings, and DB-backed TaxContext.
 */
class DefaultFederalWithholdingCalculator(
    private val method: String = "PERCENTAGE",
    private val pub15tStrictMode: Boolean = false,
) : FederalWithholdingCalculator {

    override fun computeWithholding(input: FederalWithholdingInput): Money {
        val paycheck = input.paycheckInput
        val snapshot = paycheck.employeeSnapshot

        if (snapshot.federalWithholdingExempt) {
            return Money(0L)
        }

        // Use the core EarningsCalculator to derive earnings for this paycheck
        // (including salaried allocation, hourly wages, and other earnings).
        val earnings = EarningsCalculator.computeEarnings(paycheck)

        val basisContext = BasisContext(
            earnings = earnings,
            preTaxDeductions = emptyList(),
            postTaxDeductions = emptyList(),
            plansByCode = emptyMap(),
            ytd = paycheck.priorYtd,
        )

        val basisComputation = BasisBuilder.compute(basisContext)
        val profile = WithholdingProfiles.profileFor(snapshot)

        val withholdingMethod = when (method.uppercase()) {
            "WAGE_BRACKET" -> FederalWithholdingEngine.WithholdingMethod.WAGE_BRACKET
            else -> FederalWithholdingEngine.WithholdingMethod.PERCENTAGE
        }

        val result = FederalWithholdingEngine.computeWithholding(
            input = paycheck,
            bases = basisComputation,
            profile = profile,
            federalRules = paycheck.taxContext.federal,
            method = withholdingMethod,
        )

        return result.amount
    }
}
