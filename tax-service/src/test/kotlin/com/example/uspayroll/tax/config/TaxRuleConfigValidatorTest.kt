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

    @Test
    fun `non-overlapping effective date ranges per key pass validation`() {
        val rules = listOf(
            TaxRuleConfig(
                id = "US_CA_STATE_2025_H1",
                jurisdictionType = "STATE",
                jurisdictionCode = "CA",
                basis = "StateTaxable",
                ruleType = "FLAT",
                rate = 0.05,
                annualWageCapCents = null,
                brackets = null,
                standardDeductionCents = null,
                additionalWithholdingCents = null,
                employerId = null,
                effectiveFrom = LocalDate.of(2025, 1, 1),
                effectiveTo = LocalDate.of(2025, 7, 1),
                filingStatus = "SINGLE",
                residentStateFilter = "CA",
                workStateFilter = null,
                localityFilter = null,
            ),
            TaxRuleConfig(
                id = "US_CA_STATE_2025_H2",
                jurisdictionType = "STATE",
                jurisdictionCode = "CA",
                basis = "StateTaxable",
                ruleType = "FLAT",
                rate = 0.055,
                annualWageCapCents = null,
                brackets = null,
                standardDeductionCents = null,
                additionalWithholdingCents = null,
                employerId = null,
                effectiveFrom = LocalDate.of(2025, 7, 1),
                effectiveTo = LocalDate.of(2025, 12, 31),
                filingStatus = "SINGLE",
                residentStateFilter = "CA",
                workStateFilter = null,
                localityFilter = null,
            ),
        )

        val result = TaxRuleConfigValidator.validateRules(rules)
        assertTrue(result.isValid, "Expected non-overlapping date ranges to pass validation, but got: ${result.errors}")
    }

    @Test
    fun `overlapping effective date ranges per key are rejected`() {
        val rules = listOf(
            TaxRuleConfig(
                id = "US_MI_DETROIT_LOCAL_2025_A",
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
                effectiveTo = LocalDate.of(2025, 12, 31),
                filingStatus = null,
                residentStateFilter = "MI",
                workStateFilter = null,
                localityFilter = "DETROIT",
            ),
            TaxRuleConfig(
                id = "US_MI_DETROIT_LOCAL_2025_B",
                jurisdictionType = "LOCAL",
                jurisdictionCode = "MI_DETROIT",
                basis = "StateTaxable",
                ruleType = "FLAT",
                rate = 0.03,
                annualWageCapCents = null,
                brackets = null,
                standardDeductionCents = null,
                additionalWithholdingCents = null,
                employerId = null,
                effectiveFrom = LocalDate.of(2025, 6, 1),
                effectiveTo = LocalDate.of(2025, 12, 31),
                filingStatus = null,
                residentStateFilter = "MI",
                workStateFilter = null,
                localityFilter = "DETROIT",
            ),
        )

        val result = TaxRuleConfigValidator.validateRules(rules)
        assertFalse(result.isValid, "Expected overlapping date ranges to be rejected")
        assertEquals(1, result.errors.size)
        val error = result.errors.first()
        assertEquals("US_MI_DETROIT_LOCAL_2025_B", error.ruleId)
        assertTrue(error.message.contains("Overlapping effective date ranges"))
    }
}
