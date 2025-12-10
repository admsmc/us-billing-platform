package com.example.uspayroll.tax.config

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaxRuleConfigValidatorTest {

    @Test
    fun `valid locality filters pass validation`() {
        val rules = listOf(
            TaxRuleConfig(
                id = "US_NYC_LOCAL_2025_TEST",
                jurisdictionType = "LOCAL",
                jurisdictionCode = "NYC",
                basis = "StateTaxable",
                ruleType = "FLAT",
                rate = 0.035,
                annualWageCapCents = null,
                brackets = null,
                standardDeductionCents = null,
                additionalWithholdingCents = null,
                employerId = null,
                effectiveFrom = LocalDate.of(2025, 1, 1),
                effectiveTo = LocalDate.of(9999, 12, 31),
                filingStatus = null,
                residentStateFilter = "NY",
                workStateFilter = null,
                localityFilter = "NYC",
            ),
            TaxRuleConfig(
                id = "US_MI_DETROIT_LOCAL_2025_TEST",
                jurisdictionType = "LOCAL",
                jurisdictionCode = "MI_DETROIT",
                basis = "StateTaxable",
                ruleType = "FLAT",
                rate = 0.025,
                annualWageCapCents = null,
                brackets = null,
                standardDeductionCents = null,
                additionalWithholdingCents = null,
                employerId = null,
                effectiveFrom = LocalDate.of(2025, 1, 1),
                effectiveTo = LocalDate.of(9999, 12, 31),
                filingStatus = null,
                residentStateFilter = "MI",
                workStateFilter = null,
                localityFilter = "DETROIT",
            ),
        )

        val result = TaxRuleConfigValidator.validateRules(rules)
        assertTrue(result.isValid, "Expected valid locality filters to pass validation, but got errors: ${result.errors}")
    }

    @Test
    fun `unknown locality filters are rejected`() {
        val rules = listOf(
            TaxRuleConfig(
                id = "US_MI_UNKNOWN_LOCAL_2025_TEST",
                jurisdictionType = "LOCAL",
                jurisdictionCode = "MI_UNKNOWN",
                basis = "StateTaxable",
                ruleType = "FLAT",
                rate = 0.01,
                annualWageCapCents = null,
                brackets = null,
                standardDeductionCents = null,
                additionalWithholdingCents = null,
                employerId = null,
                effectiveFrom = LocalDate.of(2025, 1, 1),
                effectiveTo = LocalDate.of(9999, 12, 31),
                filingStatus = null,
                residentStateFilter = "MI",
                workStateFilter = null,
                localityFilter = "DETROI", // typo
            ),
        )

        val result = TaxRuleConfigValidator.validateRules(rules)
        assertFalse(result.isValid, "Expected invalid result for unknown locality filter")
        assertEquals(1, result.errors.size)
        val error = result.errors.first()
        assertEquals("US_MI_UNKNOWN_LOCAL_2025_TEST", error.ruleId)
        assertTrue(error.message.contains("Unknown localityFilter"))
    }
}
