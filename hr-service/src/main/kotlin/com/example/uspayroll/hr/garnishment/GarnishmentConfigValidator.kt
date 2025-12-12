package com.example.uspayroll.hr.garnishment

import com.example.uspayroll.payroll.model.garnishment.GarnishmentType

/**
 * Validation utilities for garnishment config JSON files
 * (`garnishment-rules.json` and `garnishment-levy-bands.json`).
 *
 * The intent is similar to TaxRuleConfigValidator: catch malformed or
 * inconsistent config before it is used in HR-service or worker-service tests.
 */
object GarnishmentConfigValidator {

    data class ValidationError(
        val message: String,
    )

    data class ValidationResult(
        val errors: List<ValidationError>,
    ) {
        val isValid: Boolean get() = errors.isEmpty()
    }

    /** JSON shape for entries in garnishment-rules.json. */
    data class RuleJson(
        val employerId: String?,
        val type: GarnishmentType,
        val jurisdictionType: String?,
        val jurisdictionCode: String?,
        val percentOfDisposable: Double?,
        val protectedFloorCents: Long?,
        val description: String? = null,
        val formulaType: String? = null,
        val fixedAmountCents: Long? = null,
    )

    /** JSON shape for a single levy band within garnishment-levy-bands.json. */
    data class LevyBandJson(
        val upToCents: Long?,
        val exemptCents: Long,
        val filingStatus: String? = null,
    )

    /** JSON shape for a levy config entry in garnishment-levy-bands.json. */
    data class LevyConfigJson(
        val type: GarnishmentType,
        val jurisdictionType: String,
        val jurisdictionCode: String,
        val bands: List<LevyBandJson>,
    )

    fun validate(rules: List<RuleJson>, levyConfigs: List<LevyConfigJson>): ValidationResult {
        val errors = mutableListOf<ValidationError>()

        validateRules(rules, levyConfigs, errors)
        validateLevyBands(levyConfigs, errors)

        return ValidationResult(errors)
    }

    private fun validateRules(rules: List<RuleJson>, levyConfigs: List<LevyConfigJson>, errors: MutableList<ValidationError>) {
        if (rules.isEmpty()) return

        val validFormulaTypes = setOf(
            "PERCENT_OF_DISPOSABLE",
            "FIXED_AMOUNT_PER_PERIOD",
            "LESSER_OF_PERCENT_OR_AMOUNT",
            "LEVY_WITH_BANDS",
        )

        // 1) Duplicate detection by (employerId, type, jurisdictionType, jurisdictionCode, formulaType)
        val byKey = rules.groupBy { r ->
            RuleKey(
                employerId = r.employerId,
                type = r.type,
                jurisdictionType = r.jurisdictionType,
                jurisdictionCode = r.jurisdictionCode,
                formulaType = (r.formulaType ?: "PERCENT_OF_DISPOSABLE").uppercase(),
            )
        }
        byKey.forEach { (key, group) ->
            if (group.size > 1) {
                errors += ValidationError(
                    message = "Duplicate garnishment rule key $key appears ${group.size} times",
                )
            }
        }

        // Index levy configs by (type, jurisdictionType, jurisdictionCode)
        val levyByKey = levyConfigs.associateBy { l ->
            Triple(l.type, l.jurisdictionType.uppercase(), l.jurisdictionCode.uppercase())
        }

        // 2) Per-rule structural checks
        rules.forEach { rule ->
            val rawFormulaType = (rule.formulaType ?: "PERCENT_OF_DISPOSABLE").uppercase()

            if (rawFormulaType !in validFormulaTypes) {
                errors += ValidationError(
                    message = "Rule type=${rule.type} has unknown formulaType='$rawFormulaType' (expected one of $validFormulaTypes)",
                )
            }

            fun pctOk(): Boolean = rule.percentOfDisposable != null &&
                rule.percentOfDisposable > 0.0 &&
                rule.percentOfDisposable <= 1.0

            fun amtOk(): Boolean = rule.fixedAmountCents != null &&
                rule.fixedAmountCents > 0L

            when (rawFormulaType) {
                "PERCENT_OF_DISPOSABLE" -> {
                    if (!pctOk()) {
                        errors += ValidationError(
                            message = "Rule type=${rule.type} PERCENT_OF_DISPOSABLE must have 0 < percentOfDisposable <= 1.0 (found ${rule.percentOfDisposable})",
                        )
                    }
                }
                "FIXED_AMOUNT_PER_PERIOD" -> {
                    if (!amtOk()) {
                        errors += ValidationError(
                            message = "Rule type=${rule.type} FIXED_AMOUNT_PER_PERIOD must have positive fixedAmountCents (found ${rule.fixedAmountCents})",
                        )
                    }
                }
                "LESSER_OF_PERCENT_OR_AMOUNT" -> {
                    if (!pctOk() || !amtOk()) {
                        errors += ValidationError(
                            message = "Rule type=${rule.type} LESSER_OF_PERCENT_OR_AMOUNT must have percentOfDisposable and fixedAmountCents set to positive values (found percent=${rule.percentOfDisposable}, amount=${rule.fixedAmountCents})",
                        )
                    }
                }
                "LEVY_WITH_BANDS" -> {
                    // Ensure we have a matching levy config entry.
                    val jt = rule.jurisdictionType?.uppercase()
                    val jc = rule.jurisdictionCode?.uppercase()
                    if (jt == null || jc == null) {
                        errors += ValidationError(
                            message = "LEVY_WITH_BANDS rule type=${rule.type} must specify jurisdictionType and jurisdictionCode",
                        )
                    } else {
                        val key = Triple(rule.type, jt, jc)
                        if (!levyByKey.containsKey(key)) {
                            errors += ValidationError(
                                message = "LEVY_WITH_BANDS rule type=${rule.type} jurisdiction=($jt,$jc) has no matching levy bands in garnishment-levy-bands.json",
                            )
                        }
                    }
                }
            }
        }
    }

    private fun validateLevyBands(levyConfigs: List<LevyConfigJson>, errors: MutableList<ValidationError>) {
        if (levyConfigs.isEmpty()) return

        levyConfigs.forEach { cfg ->
            if (cfg.bands.isEmpty()) {
                errors += ValidationError(
                    message = "Levy config type=${cfg.type} jurisdiction=(${cfg.jurisdictionType},${cfg.jurisdictionCode}) must define at least one band",
                )
                return@forEach
            }

            // Group by filingStatus so we can verify sequences per status.
            val byStatus = cfg.bands.groupBy { band ->
                band.filingStatus?.uppercase() ?: "__ALL__"
            }

            byStatus.forEach { (status, bands) ->
                var sawOpenEnded = false
                var prevUpper: Long? = null

                // Sort by upToCents with null treated as open-ended max.
                val sorted = bands.sortedWith(compareBy<LevyBandJson> { it.upToCents ?: Long.MAX_VALUE })

                sorted.forEachIndexed { index, band ->
                    if (band.exemptCents <= 0L) {
                        errors += ValidationError(
                            message = "Levy band type=${cfg.type} jurisdiction=(${cfg.jurisdictionType},${cfg.jurisdictionCode}) filingStatus=$status index=$index must have positive exemptCents (found ${band.exemptCents})",
                        )
                    }

                    val upper = band.upToCents
                    if (upper == null) {
                        sawOpenEnded = true
                    } else {
                        if (upper <= 0L) {
                            errors += ValidationError(
                                message = "Levy band type=${cfg.type} jurisdiction=(${cfg.jurisdictionType},${cfg.jurisdictionCode}) filingStatus=$status index=$index has non-positive upToCents=$upper",
                            )
                        }
                        if (prevUpper != null && upper <= prevUpper!!) {
                            errors += ValidationError(
                                message = "Levy bands for type=${cfg.type} jurisdiction=(${cfg.jurisdictionType},${cfg.jurisdictionCode}) filingStatus=$status must have strictly increasing upToCents (index=$index has upToCents=$upper after $prevUpper)",
                            )
                        }
                        prevUpper = upper
                    }
                }

                if (!sawOpenEnded) {
                    errors += ValidationError(
                        message = "Levy bands for type=${cfg.type} jurisdiction=(${cfg.jurisdictionType},${cfg.jurisdictionCode}) filingStatus=$status should include an open-ended band with upToCents=null",
                    )
                }
            }
        }
    }

    /** Grouping key used for duplicate rule detection. */
    private data class RuleKey(
        val employerId: String?,
        val type: GarnishmentType,
        val jurisdictionType: String?,
        val jurisdictionCode: String?,
        val formulaType: String,
    )
}
