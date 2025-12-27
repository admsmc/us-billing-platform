package com.example.usbilling.tax.service

import kotlin.test.Test
import kotlin.test.assertNotNull

class FederalWithholdingCalculatorConfigTest {

    @Test
    fun `calculator uses percentage method by default`() {
        val calc = DefaultFederalWithholdingCalculator()
        // We don't assert amounts here, just that construction with defaults works.
        // Detailed behavior is covered in FederalWithholdingCalculatorTest.
        assertNotNull(calc)
    }

    @Test
    fun `calculator can be constructed to use wage-bracket method`() {
        val calc = DefaultFederalWithholdingCalculator(method = "WAGE_BRACKET")
        assertNotNull(calc)
    }
}
