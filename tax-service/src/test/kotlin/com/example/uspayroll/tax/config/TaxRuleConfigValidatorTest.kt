package com.example.uspayroll.tax.config

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaxRuleConfigValidatorTest {

    @Test
    fun `all supported bases and ruleTypes are accepted`() {
        val effectiveFrom = LocalDate.of(2025, 1, 1)
        val effectiveTo = LocalDate.of(9999, 12, 31)

        val bases = listOf(
            "Gross",
            "FederalTaxable",
            "StateTaxable",
            "SocialSecurityWages",
            "MedicareWages",
            "SupplementalWages",
            "FutaWages",
        )

        val ruleTypes = listOf("FLAT", "BRACKETED", "WAGE_BRACKET")

        val rules = mutableListOf<TaxRuleConfig>()

        // One FLAT rule per basis
        bases.forEachIndexed { index, basis ->
            rules += TaxRuleConfig(
                id = "TEST_FLAT_$basis",
                jurisdictionType = "STATE",
                jurisdictionCode = "ZZ",
                basis = basis,
                ruleType = "FLAT",
                rate = 0.01,
                annualWageCapCents = null,
                brackets = null,
                standardDeductionCents = null,
                additionalWithholdingCents = null,
                employerId = null,
                effectiveFrom = effectiveFrom.plusDays(index.toLong()),
                effectiveTo = effectiveTo,
                filingStatus = null,
                residentStateFilter = null,
                workStateFilter = null,
                localityFilter = null,
            )
        }

        // One BRACKETED rule and one WAGE_BRACKET rule for a canonical basis.
        rules += TaxRuleConfig(
            id = "TEST_BRACKETED",
            jurisdictionType = "FEDERAL",
            jurisdictionCode = "US_TEST",
            basis = "FederalTaxable",
            ruleType = "BRACKETED",
            rate = null,
            annualWageCapCents = null,
            brackets = listOf(
                TaxBracketConfig(upToCents = 10_000_00L, rate = 0.10),
                TaxBracketConfig(upToCents = null, rate = 0.20),
            ),
            standardDeductionCents = 1_000_00L,
            additionalWithholdingCents = null,
            employerId = null,
            effectiveFrom = effectiveFrom,
            effectiveTo = effectiveTo,
            filingStatus = "SINGLE",
            residentStateFilter = null,
            workStateFilter = null,
            localityFilter = null,
        )

        rules += TaxRuleConfig(
            id = "TEST_WAGE_BRACKET",
            jurisdictionType = "FEDERAL",
            jurisdictionCode = "US_TEST_WB",
            basis = "FederalTaxable",
            ruleType = "WAGE_BRACKET",
            rate = null,
            annualWageCapCents = null,
            brackets = listOf(
                TaxBracketConfig(upToCents = 1_000_00L, rate = 0.0, taxCents = 10_00L),
                TaxBracketConfig(upToCents = null, rate = 0.0, taxCents = 20_00L),
            ),
            standardDeductionCents = null,
            additionalWithholdingCents = null,
            employerId = null,
            effectiveFrom = effectiveFrom,
            effectiveTo = effectiveTo,
            filingStatus = "SINGLE",
            residentStateFilter = null,
            workStateFilter = null,
            localityFilter = null,
        )

        val result = TaxRuleConfigValidator.validateRules(rules)
        assertTrue(result.isValid, "Expected all supported bases and ruleTypes to pass validation, but got: ${'$'}{result.errors}")
    }

    @Test
    fun `unknown basis and ruleType are rejected`() {
        val ruleBadBasis = TaxRuleConfig(
            id = "BAD_BASIS",
            jurisdictionType = "STATE",
            jurisdictionCode = "ZZ",
            basis = "UNKNOWN_BASIS",
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
            residentStateFilter = null,
            workStateFilter = null,
            localityFilter = null,
        )

        val ruleBadType = TaxRuleConfig(
            id = "BAD_TYPE",
            jurisdictionType = "STATE",
            jurisdictionCode = "ZZ",
            basis = "Gross",
            ruleType = "PERCENTAGE", // invalid
            rate = 0.01,
            annualWageCapCents = null,
            brackets = null,
            standardDeductionCents = null,
            additionalWithholdingCents = null,
            employerId = null,
            effectiveFrom = LocalDate.of(2025, 1, 1),
            effectiveTo = LocalDate.of(9999, 12, 31),
            filingStatus = null,
            residentStateFilter = null,
            workStateFilter = null,
            localityFilter = null,
        )

        val result = TaxRuleConfigValidator.validateRules(listOf(ruleBadBasis, ruleBadType))
        assertFalse(result.isValid, "Expected invalid basis and ruleType to be rejected")
        assertTrue(result.errors.any { it.ruleId == "BAD_BASIS" && it.message.contains("Unknown basis") })
        assertTrue(result.errors.any { it.ruleId == "BAD_TYPE" && it.message.contains("Unknown ruleType") })
    }

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

    @Test
    fun `overlapping ranges are allowed for different employers with same jurisdiction`() {
        val rules = listOf(
            TaxRuleConfig(
                id = "US_CA_STATE_EMP1_2025",
                jurisdictionType = "STATE",
                jurisdictionCode = "CA",
                basis = "StateTaxable",
                ruleType = "FLAT",
                rate = 0.01,
                annualWageCapCents = null,
                brackets = null,
                standardDeductionCents = null,
                additionalWithholdingCents = null,
                employerId = "EMP1",
                effectiveFrom = LocalDate.of(2025, 1, 1),
                effectiveTo = LocalDate.of(2025, 12, 31),
                filingStatus = "SINGLE",
                residentStateFilter = "CA",
                workStateFilter = null,
                localityFilter = null,
            ),
            TaxRuleConfig(
                id = "US_CA_STATE_EMP2_2025",
                jurisdictionType = "STATE",
                jurisdictionCode = "CA",
                basis = "StateTaxable",
                ruleType = "FLAT",
                rate = 0.02,
                annualWageCapCents = null,
                brackets = null,
                standardDeductionCents = null,
                additionalWithholdingCents = null,
                employerId = "EMP2",
                effectiveFrom = LocalDate.of(2025, 1, 1),
                effectiveTo = LocalDate.of(2025, 12, 31),
                filingStatus = "SINGLE",
                residentStateFilter = "CA",
                workStateFilter = null,
                localityFilter = null,
            ),
        )

        val result = TaxRuleConfigValidator.validateRules(rules)
        assertTrue(result.isValid, "Expected overlapping ranges for different employers to be allowed, but got: ${'$'}{result.errors}")
    }

    @Test
    fun `wage-bracket rule must define taxCents for every bracket`() {
        val rules = listOf(
            TaxRuleConfig(
                id = "WB_MISSING_TAX",
                jurisdictionType = "FEDERAL",
                jurisdictionCode = "US",
                basis = "FederalTaxable",
                ruleType = "WAGE_BRACKET",
                rate = null,
                annualWageCapCents = null,
                brackets = listOf(
                    TaxBracketConfig(upToCents = 10_000_00L, rate = 0.0, taxCents = 10_00L),
                    TaxBracketConfig(upToCents = null, rate = 0.0, taxCents = null),
                ),
                standardDeductionCents = null,
                additionalWithholdingCents = null,
                employerId = null,
                effectiveFrom = LocalDate.of(2025, 1, 1),
                effectiveTo = LocalDate.of(9999, 12, 31),
                filingStatus = "SINGLE",
                residentStateFilter = null,
                workStateFilter = null,
                localityFilter = null,
            ),
        )

        val result = TaxRuleConfigValidator.validateRules(rules)
        assertFalse(result.isValid, "Expected WAGE_BRACKET rule missing taxCents to be rejected")
        assertTrue(result.errors.any { it.ruleId == "WB_MISSING_TAX" && it.message.contains("must define taxCents") })
    }

    @Test
    fun `wage-bracket rule must have strictly increasing upToCents`() {
        val rules = listOf(
            TaxRuleConfig(
                id = "WB_NON_INCREASING",
                jurisdictionType = "FEDERAL",
                jurisdictionCode = "US",
                basis = "FederalTaxable",
                ruleType = "WAGE_BRACKET",
                rate = null,
                annualWageCapCents = null,
                brackets = listOf(
                    TaxBracketConfig(upToCents = 10_000_00L, rate = 0.0, taxCents = 10_00L),
                    TaxBracketConfig(upToCents = 9_000_00L, rate = 0.0, taxCents = 20_00L),
                    TaxBracketConfig(upToCents = null, rate = 0.0, taxCents = 30_00L),
                ),
                standardDeductionCents = null,
                additionalWithholdingCents = null,
                employerId = null,
                effectiveFrom = LocalDate.of(2025, 1, 1),
                effectiveTo = LocalDate.of(9999, 12, 31),
                filingStatus = "SINGLE",
                residentStateFilter = null,
                workStateFilter = null,
                localityFilter = null,
            ),
        )

        val result = TaxRuleConfigValidator.validateRules(rules)
        assertFalse(result.isValid, "Expected WAGE_BRACKET rule with non-increasing upToCents to be rejected")
        assertTrue(result.errors.any { it.ruleId == "WB_NON_INCREASING" && it.message.contains("strictly increasing upToCents") })
    }

    @Test
    fun `wage-bracket rule must include at least one open-ended bracket`() {
        val rules = listOf(
            TaxRuleConfig(
                id = "WB_NO_OPEN_ENDED",
                jurisdictionType = "FEDERAL",
                jurisdictionCode = "US",
                basis = "FederalTaxable",
                ruleType = "WAGE_BRACKET",
                rate = null,
                annualWageCapCents = null,
                brackets = listOf(
                    TaxBracketConfig(upToCents = 10_000_00L, rate = 0.0, taxCents = 10_00L),
                    TaxBracketConfig(upToCents = 20_000_00L, rate = 0.0, taxCents = 20_00L),
                ),
                standardDeductionCents = null,
                additionalWithholdingCents = null,
                employerId = null,
                effectiveFrom = LocalDate.of(2025, 1, 1),
                effectiveTo = LocalDate.of(9999, 12, 31),
                filingStatus = "SINGLE",
                residentStateFilter = null,
                workStateFilter = null,
                localityFilter = null,
            ),
        )

        val result = TaxRuleConfigValidator.validateRules(rules)
        assertFalse(result.isValid, "Expected WAGE_BRACKET rule without open-ended bracket to be rejected")
        assertTrue(result.errors.any { it.ruleId == "WB_NO_OPEN_ENDED" && it.message.contains("open-ended bracket") })
    }
}
