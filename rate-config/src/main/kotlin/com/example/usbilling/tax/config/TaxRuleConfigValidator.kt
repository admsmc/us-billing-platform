package com.example.usbilling.tax.config

/**
 * Validation utilities for [TaxRuleConfig] / [TaxRuleFile]. These are intended
 * to catch common configuration issues *before* importing rules into the
 * `tax_rule` table or using them in tests.
 */
object TaxRuleConfigValidator {

    data class ValidationError(
        val ruleId: String?,
        val message: String,
    )

    data class ValidationResult(
        val errors: List<ValidationError>,
    ) {
        val isValid: Boolean get() = errors.isEmpty()
    }

    private val validBases = setOf(
        "Gross",
        "FederalTaxable",
        "StateTaxable",
        "SocialSecurityWages",
        "MedicareWages",
        "SupplementalWages",
        "FutaWages",
    )

    private val validRuleTypes = setOf("FLAT", "BRACKETED", "WAGE_BRACKET")

    /**
     * Registry of known locality codes used by tax-service. This is intentionally
     * small for now (NYC and a few Michigan cities) but can be expanded as
     * additional locals are modeled.
     */
    private val validLocalities = setOf(
        "NYC",
        "DETROIT",
        "GRAND_RAPIDS",
        "LANSING",
        "PHILADELPHIA",
        "ST_LOUIS",
        "KANSAS_CITY",
        "COLUMBUS",
        "CLEVELAND",
        "CINCINNATI",
        "AKRON",
        "DAYTON",
        "TOLEDO",
        "YOUNGSTOWN",
        "BALTIMORE_CITY",
        "BIRMINGHAM",
        "WILMINGTON",
        "MARION_COUNTY",
        "LOUISVILLE",
        "PORTLAND_METRO_SHS",
        "MULTNOMAH_PFA",
    )

    fun validateFile(file: TaxRuleFile): ValidationResult = validateRules(file.rules)

    fun validateRules(rules: List<TaxRuleConfig>): ValidationResult {
        val errors = mutableListOf<ValidationError>()

        // 1. Duplicate IDs
        val byId = rules.groupBy { it.id }
        byId.forEach { (id, group) ->
            if (group.size > 1) {
                errors += ValidationError(
                    ruleId = id,
                    message = "Duplicate rule id '$id' appears ${group.size} times",
                )
            }
        }

        // 2. Per-rule structural checks
        rules.forEach { rule ->
            // Basis must be known
            if (rule.basis !in validBases) {
                errors += ValidationError(
                    ruleId = rule.id,
                    message = "Unknown basis '${rule.basis}' (expected one of $validBases)",
                )
            }

            // Rule type must be known
            if (rule.ruleType !in validRuleTypes) {
                errors += ValidationError(
                    ruleId = rule.id,
                    message = "Unknown ruleType '${rule.ruleType}' (expected one of $validRuleTypes)",
                )
            }

            // Effective dates: require from < to
            if (!rule.effectiveFrom.isBefore(rule.effectiveTo)) {
                errors += ValidationError(
                    ruleId = rule.id,
                    message = "effectiveFrom=${rule.effectiveFrom} must be before effectiveTo=${rule.effectiveTo}",
                )
            }

            // Locality filter, when provided, must be in the registry of known
            // localities. This helps catch typos like "DETROI" early.
            val locality = rule.localityFilter
            if (!locality.isNullOrBlank() && locality !in validLocalities) {
                errors += ValidationError(
                    ruleId = rule.id,
                    message = "Unknown localityFilter '$locality' (expected one of $validLocalities)",
                )
            }

            // Structural checks for FLAT vs BRACKETED vs WAGE_BRACKET
            when (rule.ruleType) {
                "FLAT" -> {
                    if (rule.rate == null) {
                        errors += ValidationError(
                            ruleId = rule.id,
                            message = "FLAT rule must have non-null rate",
                        )
                    }
                    if (rule.brackets != null) {
                        errors += ValidationError(
                            ruleId = rule.id,
                            message = "FLAT rule must not define brackets (found ${rule.brackets.size})",
                        )
                    }
                }
                "BRACKETED" -> {
                    if (rule.brackets.isNullOrEmpty()) {
                        errors += ValidationError(
                            ruleId = rule.id,
                            message = "BRACKETED rule must have at least one bracket",
                        )
                    }
                    // For pure bracketed income tax we expect rate=null; we allow
                    // non-null rate only if we later introduce hybrid semantics.
                    if (rule.rate != null) {
                        errors += ValidationError(
                            ruleId = rule.id,
                            message = "BRACKETED rule should not have flat rate field set (found rate=${rule.rate})",
                        )
                    }
                    rule.brackets?.forEachIndexed { index, bracket ->
                        if (bracket.rate <= 0.0 && bracket.upToCents == null) {
                            errors += ValidationError(
                                ruleId = rule.id,
                                message = "Top bracket (index $index) has non-positive rate=${bracket.rate} and no upper bound",
                            )
                        }
                    }
                }
                "WAGE_BRACKET" -> {
                    val brackets = rule.brackets
                    if (brackets.isNullOrEmpty()) {
                        errors += ValidationError(
                            ruleId = rule.id,
                            message = "WAGE_BRACKET rule must have at least one bracket",
                        )
                    } else {
                        var sawOpenEnded = false
                        var prevUpper: Long? = null
                        brackets.forEachIndexed { index, bracket ->
                            if (bracket.taxCents == null) {
                                errors += ValidationError(
                                    ruleId = rule.id,
                                    message = "WAGE_BRACKET bracket index $index must define taxCents",
                                )
                            }
                            val upper = bracket.upToCents
                            if (upper == null) {
                                sawOpenEnded = true
                            } else {
                                if (prevUpper != null && upper <= prevUpper!!) {
                                    errors += ValidationError(
                                        ruleId = rule.id,
                                        message = "WAGE_BRACKET brackets must have strictly increasing upToCents (index $index has upToCents=$upper after $prevUpper)",
                                    )
                                }
                                prevUpper = upper
                            }
                        }
                        if (!sawOpenEnded) {
                            errors += ValidationError(
                                ruleId = rule.id,
                                message = "WAGE_BRACKET rule should have at least one open-ended bracket (upToCents=null)",
                            )
                        }
                    }
                }
            }
        }

        // 3. Overlapping effective date range checks per key
        //
        // For each combination of (jurisdictionType, jurisdictionCode,
        // employerId, localityFilter, filingStatus), assert that effective
        // date ranges do not overlap. Boundaries that touch (to == from) are
        // allowed.
        val byKey = rules.groupBy { rule ->
            Key(
                jurisdictionType = rule.jurisdictionType,
                jurisdictionCode = rule.jurisdictionCode,
                employerId = rule.employerId,
                localityFilter = rule.localityFilter,
                filingStatus = rule.filingStatus,
                fitVariant = rule.fitVariant,
            )
        }

        byKey.forEach { (key, group) ->
            if (group.size <= 1) return@forEach
            val sorted = group.sortedBy { it.effectiveFrom }
            var previous = sorted.first()
            for (i in 1 until sorted.size) {
                val current = sorted[i]
                if (current.effectiveFrom.isBefore(previous.effectiveTo)) {
                    errors += ValidationError(
                        ruleId = current.id,
                        message =
                        "Overlapping effective date ranges for key $key between ${previous.id} " +
                            "[${previous.effectiveFrom}, ${previous.effectiveTo}) and ${current.id} " +
                            "[${current.effectiveFrom}, ${current.effectiveTo})",
                    )
                }
                if (current.effectiveTo.isAfter(previous.effectiveTo)) {
                    previous = current
                }
            }
        }

        return ValidationResult(errors)
    }

    /** Grouping key for overlapping-range validation. */
    private data class Key(
        val jurisdictionType: String,
        val jurisdictionCode: String,
        val employerId: String?,
        val localityFilter: String?,
        val filingStatus: String?,
        val fitVariant: String?,
    )
}
