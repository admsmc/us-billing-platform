package com.example.uspayroll.payroll.engine

import com.example.uspayroll.payroll.model.*
import com.example.uspayroll.shared.Money
import kotlin.test.Test
import kotlin.test.assertEquals

class BasisBuilderEffectsTest {

    @Test
    fun `pre tax plan reduces federal taxable while roth plan does not`() {
        val earnings = listOf(
            EarningLine(
                code = EarningCode("REG"),
                category = EarningCategory.REGULAR,
                description = "Regular",
                units = 1.0,
                rate = null,
                amount = Money(10_000_00L),
            ),
        )

        val pretax = DeductionLine(
            code = DeductionCode("PRETAX_RET"),
            description = "Pre-tax retirement",
            amount = Money(1_000_00L),
        )
        val roth = DeductionLine(
            code = DeductionCode("ROTH_RET"),
            description = "Roth retirement",
            amount = Money(1_000_00L),
        )

        val plansByCode = mapOf(
            DeductionCode("PRETAX_RET") to com.example.uspayroll.payroll.model.config.DeductionPlan(
                id = "PRETAX_RET",
                name = "Pre-tax retirement",
                kind = com.example.uspayroll.payroll.model.config.DeductionKind.PRETAX_RETIREMENT_EMPLOYEE,
            ),
            DeductionCode("ROTH_RET") to com.example.uspayroll.payroll.model.config.DeductionPlan(
                id = "ROTH_RET",
                name = "Roth retirement",
                kind = com.example.uspayroll.payroll.model.config.DeductionKind.ROTH_RETIREMENT_EMPLOYEE,
            ),
        )

        val context = BasisContext(
            earnings = earnings,
            preTaxDeductions = listOf(pretax, roth),
            postTaxDeductions = emptyList(),
            plansByCode = plansByCode,
            ytd = YtdSnapshot(year = 2025),
        )

        val basisComputation = BasisBuilder.compute(context)
        val bases = basisComputation.bases

        val gross = bases[TaxBasis.Gross] ?: error("missing gross basis")
        val federalTaxable = bases[TaxBasis.FederalTaxable] ?: error("missing federal taxable basis")

        // With plan metadata, only the pre-tax retirement plan reduces federal taxable;
        // the Roth retirement plan has NO_TAX_EFFECT by default.
        assertEquals(10_000_00L, gross.amount)
        assertEquals(9_000_00L, federalTaxable.amount)
    }
}
