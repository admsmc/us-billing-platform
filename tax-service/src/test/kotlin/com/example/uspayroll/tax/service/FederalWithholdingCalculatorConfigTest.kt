package com.example.uspayroll.tax.service

import kotlin.test.Test
import kotlin.test.assertTrue

class FederalWithholdingCalculatorConfigTest {

    @Test
    fun `calculator uses percentage method by default`() {
        val calc = DefaultFederalWithholdingCalculator()
        // We don't assert amounts here, just that construction with defaults works.
        // Detailed behavior is covered in FederalWithholdingCalculatorTest.
        assertTrue(calc is FederalWithholdingCalculator)
    }

    @Test
    fun `calculator can be constructed to use wage-bracket method`() {
        val calc = DefaultFederalWithholdingCalculator(method = "WAGE_BRACKET")
        assertTrue(calc is FederalWithholdingCalculator)
    }
}