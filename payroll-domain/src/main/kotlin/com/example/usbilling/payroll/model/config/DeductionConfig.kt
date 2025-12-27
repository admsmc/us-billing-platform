package com.example.usbilling.payroll.model.config

import com.example.usbilling.payroll.model.Percent
import com.example.usbilling.payroll.model.garnishment.GarnishmentType
import com.example.usbilling.shared.UtilityId
import com.example.usbilling.shared.Money

/**
 * System-defined semantic kinds of deductions/benefits.
 * These determine tax treatment; employers choose which plans of each kind to offer.
 *
 * The core engine uses behavioral categories (e.g. PRETAX_RETIREMENT_EMPLOYEE)
 * while specific plan types (401k vs 403b vs 457b) are modeled at the plan
 * level via [DeductionPlan] metadata.
 */
enum class DeductionKind {
    PRETAX_RETIREMENT_EMPLOYEE,
    ROTH_RETIREMENT_EMPLOYEE,
    HSA,
    FSA,
    POSTTAX_VOLUNTARY,
    GARNISHMENT,
    OTHER_POSTTAX,
}

/**
 * How a deduction affects various employee tax bases.
 */
enum class DeductionEffect {
    REDUCES_FEDERAL_TAXABLE,
    REDUCES_STATE_TAXABLE,
    REDUCES_SOCIAL_SECURITY_WAGES,
    REDUCES_MEDICARE_WAGES,
    NO_TAX_EFFECT,
}

/****
 * Employer-configurable deduction/benefit plan.
 * Tax treatment is implied by [kind] and can be refined via [employeeEffects];
 * the amount/cap fields control how much is withheld.
 */
data class DeductionPlan(
    val id: String,
    val name: String,
    val kind: DeductionKind,
    /**
     * Optional subtype or statutory code for reporting (e.g. "401k", "403b", "457b").
     * The engine only cares about [kind]; reporting layers can use [subtype].
     */
    val subtype: String? = null,
    /**
     * Optional explicit garnishment type for plans where [kind] is
     * [DeductionKind.GARNISHMENT]. This mirrors [GarnishmentType] and can be
     * used by configuration and reporting layers to link employer plans to
     * specific statutory order types (e.g., CHILD_SUPPORT vs CREDITOR_GARNISHMENT).
     *
     * The core engine does not currently rely on this field; it derives
     * garnishment behavior from [GarnishmentOrder.type] and [kind].
     */
    val garnishmentType: GarnishmentType? = null,
    val employeeRate: Percent? = null,
    val employeeFlat: Money? = null,
    val employerRate: Percent? = null,
    val employerFlat: Money? = null,
    val annualCap: Money? = null,
    val perPeriodCap: Money? = null,
    val employeeEffects: Set<DeductionEffect> = emptySet(),
)

/**
 * Port interface for loading deduction plans for an employer.
 */
interface DeductionConfigRepository {
    fun findPlansForEmployer(employerId: UtilityId): List<DeductionPlan>
}

/**
 * Default tax-base effects per deduction kind.
 * These can be overridden by setting [DeductionPlan.employeeEffects].
 */
fun DeductionKind.defaultEmployeeEffects(): Set<DeductionEffect> = when (this) {
    DeductionKind.PRETAX_RETIREMENT_EMPLOYEE -> setOf(
        DeductionEffect.REDUCES_FEDERAL_TAXABLE,
        DeductionEffect.REDUCES_STATE_TAXABLE,
    )
    DeductionKind.ROTH_RETIREMENT_EMPLOYEE -> setOf(DeductionEffect.NO_TAX_EFFECT)
    DeductionKind.HSA -> setOf(
        DeductionEffect.REDUCES_FEDERAL_TAXABLE,
        DeductionEffect.REDUCES_STATE_TAXABLE,
        DeductionEffect.REDUCES_SOCIAL_SECURITY_WAGES,
        DeductionEffect.REDUCES_MEDICARE_WAGES,
    )
    DeductionKind.FSA -> setOf(
        DeductionEffect.REDUCES_FEDERAL_TAXABLE,
        DeductionEffect.REDUCES_STATE_TAXABLE,
    )
    DeductionKind.POSTTAX_VOLUNTARY -> setOf(DeductionEffect.NO_TAX_EFFECT)
    DeductionKind.GARNISHMENT -> setOf(DeductionEffect.NO_TAX_EFFECT)
    DeductionKind.OTHER_POSTTAX -> setOf(DeductionEffect.NO_TAX_EFFECT)
}
