package com.example.uspayroll.tax.http

import com.example.uspayroll.payroll.model.FilingStatus
import com.example.uspayroll.payroll.model.Percent
import com.example.uspayroll.payroll.model.TaxBasis
import com.example.uspayroll.payroll.model.TaxBracket
import com.example.uspayroll.payroll.model.TaxContext
import com.example.uspayroll.payroll.model.TaxJurisdiction
import com.example.uspayroll.payroll.model.TaxJurisdictionType
import com.example.uspayroll.payroll.model.TaxRule
import com.example.uspayroll.payroll.model.WageBracketRow
import com.example.uspayroll.shared.Money

/**
 * Stable wire DTOs for the tax-service HTTP API.
 *
 * These are intentionally decoupled from the internal payroll-domain models so
 * that we can evolve engine internals without breaking the external contract.
 */

data class TaxContextDto(
    val federal: List<TaxRuleDto> = emptyList(),
    val state: List<TaxRuleDto> = emptyList(),
    val local: List<TaxRuleDto> = emptyList(),
    val employerSpecific: List<TaxRuleDto> = emptyList(),
)

enum class TaxRuleKindDto { FLAT, BRACKETED, WAGE_BRACKET }

data class TaxRuleDto(
    val id: String,
    val jurisdictionType: TaxJurisdictionType,
    val jurisdictionCode: String,
    val basis: String,
    val kind: TaxRuleKindDto,
    val rate: Double? = null,
    val annualWageCapCents: Long? = null,
    val brackets: List<TaxBracketDto> = emptyList(),
    val standardDeductionCents: Long? = null,
    val additionalWithholdingCents: Long? = null,
    val filingStatus: FilingStatus? = null,
    val localityFilter: String? = null,
)

data class TaxBracketDto(
    val upToCents: Long?,
    val rate: Double?,
    val taxCents: Long? = null,
)

// --- Mapping: domain -> DTO ---

fun TaxContext.toDto(): TaxContextDto = TaxContextDto(
    federal = federal.map { it.toDto() },
    state = state.map { it.toDto() },
    local = local.map { it.toDto() },
    employerSpecific = employerSpecific.map { it.toDto() },
)

private fun TaxRule.toDto(): TaxRuleDto = when (this) {
    is TaxRule.FlatRateTax -> TaxRuleDto(
        id = id,
        jurisdictionType = jurisdiction.type,
        jurisdictionCode = jurisdiction.code,
        basis = basis.toWireName(),
        kind = TaxRuleKindDto.FLAT,
        rate = rate.value,
        annualWageCapCents = annualWageCap?.amount,
        localityFilter = localityFilter,
    )
    is TaxRule.BracketedIncomeTax -> TaxRuleDto(
        id = id,
        jurisdictionType = jurisdiction.type,
        jurisdictionCode = jurisdiction.code,
        basis = basis.toWireName(),
        kind = TaxRuleKindDto.BRACKETED,
        brackets = brackets.map { it.toDto() },
        standardDeductionCents = standardDeduction?.amount,
        additionalWithholdingCents = additionalWithholding?.amount,
        filingStatus = filingStatus,
        localityFilter = localityFilter,
    )
    is TaxRule.WageBracketTax -> TaxRuleDto(
        id = id,
        jurisdictionType = jurisdiction.type,
        jurisdictionCode = jurisdiction.code,
        basis = basis.toWireName(),
        kind = TaxRuleKindDto.WAGE_BRACKET,
        brackets = brackets.map { it.toDto() },
        filingStatus = filingStatus,
        localityFilter = localityFilter,
    )
}

private fun TaxBracket.toDto(): TaxBracketDto = TaxBracketDto(
    upToCents = upTo?.amount,
    rate = rate.value,
    taxCents = null,
)

private fun WageBracketRow.toDto(): TaxBracketDto = TaxBracketDto(
    upToCents = upTo?.amount,
    rate = null,
    taxCents = tax.amount,
)

private fun TaxBasis.toWireName(): String = when (this) {
    TaxBasis.Gross -> "Gross"
    TaxBasis.FederalTaxable -> "FederalTaxable"
    TaxBasis.StateTaxable -> "StateTaxable"
    TaxBasis.SocialSecurityWages -> "SocialSecurityWages"
    TaxBasis.MedicareWages -> "MedicareWages"
    TaxBasis.SupplementalWages -> "SupplementalWages"
    TaxBasis.FutaWages -> "FutaWages"
}

// --- Mapping: DTO -> domain ---

fun TaxContextDto.toDomain(): TaxContext = TaxContext(
    federal = federal.map { it.toDomain() },
    state = state.map { it.toDomain() },
    local = local.map { it.toDomain() },
    employerSpecific = employerSpecific.map { it.toDomain() },
)

fun TaxRuleDto.toDomain(): TaxRule {
    val jurisdiction = TaxJurisdiction(
        type = jurisdictionType,
        code = jurisdictionCode,
    )

    return when (kind) {
        TaxRuleKindDto.FLAT -> TaxRule.FlatRateTax(
            id = id,
            jurisdiction = jurisdiction,
            basis = basis.toDomainBasis(),
            rate = Percent(requireNotNull(rate) { "Flat rule '$id' missing rate" }),
            annualWageCap = annualWageCapCents?.let { Money(it) },
            localityFilter = localityFilter,
        )
        TaxRuleKindDto.BRACKETED -> TaxRule.BracketedIncomeTax(
            id = id,
            jurisdiction = jurisdiction,
            basis = basis.toDomainBasis(),
            brackets = brackets.map { it.toDomainBracket() },
            standardDeduction = standardDeductionCents?.let { Money(it) },
            additionalWithholding = additionalWithholdingCents?.let { Money(it) },
            filingStatus = filingStatus,
            localityFilter = localityFilter,
        )
        TaxRuleKindDto.WAGE_BRACKET -> TaxRule.WageBracketTax(
            id = id,
            jurisdiction = jurisdiction,
            basis = basis.toDomainBasis(),
            brackets = brackets.map { it.toDomainWageRow() },
            filingStatus = filingStatus,
            localityFilter = localityFilter,
        )
    }
}

private fun TaxBracketDto.toDomainBracket(): TaxBracket {
    val rateValue = requireNotNull(rate) { "Bracket for rule is missing rate" }
    return TaxBracket(
        upTo = upToCents?.let { Money(it) },
        rate = Percent(rateValue),
    )
}

private fun TaxBracketDto.toDomainWageRow(): WageBracketRow {
    val taxAmount = requireNotNull(taxCents) { "Wage bracket row is missing taxCents" }
    return WageBracketRow(
        upTo = upToCents?.let { Money(it) },
        tax = Money(taxAmount),
    )
}

private fun String.toDomainBasis(): TaxBasis = when (this) {
    "Gross" -> TaxBasis.Gross
    "FederalTaxable" -> TaxBasis.FederalTaxable
    "StateTaxable" -> TaxBasis.StateTaxable
    "SocialSecurityWages" -> TaxBasis.SocialSecurityWages
    "MedicareWages" -> TaxBasis.MedicareWages
    "SupplementalWages" -> TaxBasis.SupplementalWages
    "FutaWages" -> TaxBasis.FutaWages
    else -> error("Unknown TaxBasis '$this' from TaxRuleDto.basis")
}
