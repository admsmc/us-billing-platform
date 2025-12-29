package com.example.usbilling.billing.model

import com.example.usbilling.shared.Money

/**
 * A regulatory surcharge or rider that can be applied to utility bills.
 * These are typically mandated by regulatory commissions (e.g., Michigan Public Service Commission).
 *
 * @property code Surcharge code (e.g., "PSCR", "SAF", "LIHEAP")
 * @property description Human-readable description
 * @property calculationType How this surcharge is calculated
 * @property fixedAmount Fixed dollar amount (for FIXED type)
 * @property ratePerUnit Rate per usage unit (for PER_UNIT type)
 * @property percentageRate Percentage rate as decimal (e.g., 0.5 for 0.5%)
 * @property appliesTo Which service types this surcharge applies to
 */
data class RegulatorySurcharge(
    val code: String,
    val description: String,
    val calculationType: RegulatorySurchargeCalculation,
    val fixedAmount: Money? = null,
    val ratePerUnit: Money? = null,
    val percentageRate: Double? = null,
    val appliesTo: Set<ServiceType> = emptySet(),
) {
    init {
        // Validate that appropriate field is set for calculation type
        when (calculationType) {
            RegulatorySurchargeCalculation.FIXED -> {
                require(fixedAmount != null) { "fixedAmount must be set for FIXED calculation type" }
            }
            RegulatorySurchargeCalculation.PER_UNIT -> {
                require(ratePerUnit != null) { "ratePerUnit must be set for PER_UNIT calculation type" }
            }
            RegulatorySurchargeCalculation.PERCENTAGE_OF_ENERGY,
            RegulatorySurchargeCalculation.PERCENTAGE_OF_TOTAL,
            -> {
                require(percentageRate != null) { "percentageRate must be set for percentage calculation types" }
            }
        }
    }

    /**
     * Check if this surcharge applies to a given service type.
     */
    fun appliesTo(serviceType: ServiceType): Boolean = appliesTo.isEmpty() || appliesTo.contains(serviceType)
}

/**
 * How a regulatory surcharge is calculated.
 */
enum class RegulatorySurchargeCalculation {
    /** Fixed dollar amount per bill */
    FIXED,

    /** Charge per unit of consumption ($/kWh, $/CCF, etc.) */
    PER_UNIT,

    /** Percentage of energy/usage charges only */
    PERCENTAGE_OF_ENERGY,

    /** Percentage of total bill amount */
    PERCENTAGE_OF_TOTAL,
}
