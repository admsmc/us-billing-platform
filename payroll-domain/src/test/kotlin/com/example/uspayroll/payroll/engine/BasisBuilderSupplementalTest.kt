package com.example.uspayroll.payroll.engine

import com.example.uspayroll.shared.Money
import com.example.uspayroll.payroll.model.*
import kotlin.test.Test
import kotlin.test.assertEquals

class BasisBuilderSupplementalTest {

    @Test
    fun `gross components include supplemental and holiday breakdowns`() {
        val earnings = listOf(
            EarningLine(
                code = EarningCode("REG"),
                category = EarningCategory.REGULAR,
                description = "Regular",
                units = 1.0,
                rate = Money(1_000_00L),
                amount = Money(1_000_00L),
            ),
            EarningLine(
                code = EarningCode("BONUS"),
                category = EarningCategory.SUPPLEMENTAL,
                description = "Bonus",
                units = 1.0,
                rate = Money(500_00L),
                amount = Money(500_00L),
            ),
            EarningLine(
                code = EarningCode("HOL"),
                category = EarningCategory.HOLIDAY,
                description = "Holiday",
                units = 1.0,
                rate = Money(300_00L),
                amount = Money(300_00L),
            ),
        )

        val context = BasisContext(
            earnings = earnings,
            preTaxDeductions = emptyList(),
            postTaxDeductions = emptyList(),
            plansByCode = emptyMap(),
            ytd = YtdSnapshot(year = 2025),
        )

        val computation = BasisBuilder.compute(context)
        val grossComponents = computation.components[TaxBasis.Gross] ?: emptyMap()

        assertEquals(1_800_00L, computation.bases[TaxBasis.Gross]?.amount)
        assertEquals(500_00L, grossComponents["supplemental"]?.amount)
        assertEquals(300_00L, grossComponents["holiday"]?.amount)
    }
}
