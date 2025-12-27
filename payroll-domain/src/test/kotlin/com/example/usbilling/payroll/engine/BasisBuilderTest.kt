package com.example.usbilling.payroll.engine

import com.example.usbilling.payroll.model.*
import com.example.usbilling.shared.Money
import kotlin.test.Test
import kotlin.test.assertEquals

class BasisBuilderTest {

    @Test
    fun `gross and federal taxable bases computed from earnings and pre tax deductions`() {
        val employerId = com.example.usbilling.shared.EmployerId("emp-basis")
        val period = PayPeriod(
            id = "PERIOD-1",
            employerId = employerId,
            dateRange = LocalDateRange(java.time.LocalDate.of(2025, 1, 1), java.time.LocalDate.of(2025, 1, 14)),
            checkDate = java.time.LocalDate.of(2025, 1, 15),
            frequency = PayFrequency.BIWEEKLY,
        )

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

        val preTax = listOf(
            DeductionLine(
                code = DeductionCode("PRETAX_1"),
                description = "Pre-tax",
                amount = Money(1_000_00L),
            ),
        )

        val postTax = listOf<DeductionLine>()

        val context = BasisContext(
            earnings = earnings,
            preTaxDeductions = preTax,
            postTaxDeductions = postTax,
            plansByCode = emptyMap(),
            ytd = YtdSnapshot(year = 2025),
        )

        val basisComputation = BasisBuilder.compute(context)
        val bases = basisComputation.bases

        val gross = bases[TaxBasis.Gross] ?: error("missing gross basis")
        val federalTaxable = bases[TaxBasis.FederalTaxable] ?: error("missing federal taxable basis")

        assertEquals(10_000_00L, gross.amount)
        assertEquals(9_000_00L, federalTaxable.amount)
    }
}
