package com.example.usbilling.tax.tools

import com.example.usbilling.payroll.model.FilingStatus
import com.example.usbilling.payroll.model.PayFrequency
import com.example.usbilling.tax.config.TaxRuleConfigValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Pub15TWageBracketGeneratorTest {

    @Test
    fun `generateFromResource produces valid WAGE_BRACKET config for SINGLE BIWEEKLY`() {
        val file = Pub15TWageBracketGenerator.generateFromResource(
            pub15tResource = "tax-config/federal-2025-pub15t.json",
            filingStatus = FilingStatus.SINGLE,
            frequency = PayFrequency.BIWEEKLY,
            minCents = 0L,
            maxCents = 500_000L,
            stepCents = 5_000L,
        )

        // Validate with the same config validator used for static JSON files.
        val validation = TaxRuleConfigValidator.validateFile(file)
        assertTrue(validation.isValid, "Expected generated WAGE_BRACKET file to be valid, but got ${validation.errors}")

        // Structural sanity checks.
        assertEquals(1, file.rules.size, "Generator should emit a single WAGE_BRACKET rule")
        val rule = file.rules.first()

        assertEquals("WAGE_BRACKET", rule.ruleType)
        val brackets = rule.brackets ?: error("Generated WAGE_BRACKET rule must have brackets")
        assertTrue(brackets.isNotEmpty(), "Generated WAGE_BRACKET rule must have at least one bracket")

        // All brackets should have taxCents defined.
        brackets.forEachIndexed { index, b ->
            assertTrue(b.taxCents != null, "Bracket index $index must have taxCents set")
        }

        // upToCents should be strictly increasing where non-null, with a final open-ended band.
        var prevUpper: Long? = null
        brackets.forEachIndexed { index, b ->
            val upper = b.upToCents
            if (upper != null) {
                if (prevUpper != null) {
                    assertTrue(
                        upper > prevUpper!!,
                        "Bracket index $index has upToCents=$upper which is not > previous $prevUpper",
                    )
                }
                prevUpper = upper
            }
        }
        assertTrue(
            brackets.any { it.upToCents == null },
            "Generated WAGE_BRACKET rule should include an open-ended bracket (upToCents=null)",
        )

        // Ensure taxCents is non-decreasing across brackets for monotonicity.
        val taxes = brackets.map { it.taxCents!! }
        assertTrue(
            taxes.zipWithNext().all { (a, b) -> a <= b },
            "Expected non-decreasing taxCents across generated brackets, got $taxes",
        )
    }
}
