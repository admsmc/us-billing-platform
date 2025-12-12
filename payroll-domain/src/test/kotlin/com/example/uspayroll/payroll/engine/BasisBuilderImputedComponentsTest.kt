package com.example.uspayroll.payroll.engine

import com.example.uspayroll.payroll.model.*
import com.example.uspayroll.shared.Money
import kotlin.test.Test
import kotlin.test.assertEquals

class BasisBuilderImputedComponentsTest {

    @Test
    fun `gross components include imputed breakdown`() {
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
                code = EarningCode("IMPUTED_GTL"),
                category = EarningCategory.IMPUTED,
                description = "Imputed GTL",
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

        assertEquals(1_300_00L, computation.bases[TaxBasis.Gross]?.amount)
        assertEquals(300_00L, grossComponents["imputed"]?.amount)
    }
}
