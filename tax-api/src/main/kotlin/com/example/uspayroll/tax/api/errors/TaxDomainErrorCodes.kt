package com.example.uspayroll.tax.api.errors

/**
 * Stable, machine-readable domain error codes for the Tax bounded context.
 *
 * Format: `tax.<noun>_<reason>`
 * Example: `tax.rule_invalid`
 */
object TaxDomainErrorCodes {

    const val RULE_INVALID: String = "tax.rule_invalid"
    const val RULE_NOT_FOUND: String = "tax.rule_not_found"

    const val TAX_CONTEXT_UNAVAILABLE: String = "tax.tax_context_unavailable"
    const val TAX_CONTEXT_NOT_FOUND: String = "tax.tax_context_not_found"

    const val LOCALITY_CODE_UNKNOWN: String = "tax.locality_code_unknown"
    const val JURISDICTION_CODE_UNKNOWN: String = "tax.jurisdiction_code_unknown"

    const val CONFIG_INVALID: String = "tax.config_invalid"
}
