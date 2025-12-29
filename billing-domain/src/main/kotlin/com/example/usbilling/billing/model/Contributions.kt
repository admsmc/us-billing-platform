package com.example.usbilling.billing.model

import com.example.usbilling.shared.Money

/**
 * A voluntary contribution or donation added to a customer's bill.
 *
 * @property code Contribution code (e.g., "ENERGY_ASSIST", "TREE_PLANT")
 * @property description Human-readable description for bill display
 * @property amount Contribution amount
 * @property program The program this contribution supports
 */
data class VoluntaryContribution(
    val code: String,
    val description: String,
    val amount: Money,
    val program: ContributionProgram,
)

/**
 * Types of contribution programs offered by utilities.
 */
enum class ContributionProgram {
    /** Low-income energy assistance programs */
    ENERGY_ASSISTANCE,

    /** Tree planting and urban forestry programs */
    TREE_PLANTING,

    /** Renewable energy development funds */
    RENEWABLE_ENERGY,

    /** General low-income customer support */
    LOW_INCOME_SUPPORT,

    /** Community improvement fund */
    COMMUNITY_FUND,

    /** Environmental conservation programs */
    CONSERVATION,

    /** Education and outreach programs */
    EDUCATION,

    /** Other/miscellaneous programs */
    OTHER,

    ;

    /**
     * Human-readable display name for the program.
     */
    fun displayName(): String = when (this) {
        ENERGY_ASSISTANCE -> "Energy Assistance Program"
        TREE_PLANTING -> "Tree Planting Program"
        RENEWABLE_ENERGY -> "Renewable Energy Fund"
        LOW_INCOME_SUPPORT -> "Low Income Support"
        COMMUNITY_FUND -> "Community Improvement Fund"
        CONSERVATION -> "Environmental Conservation"
        EDUCATION -> "Education & Outreach"
        OTHER -> "Other Programs"
    }
}
