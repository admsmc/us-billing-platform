package com.example.usbilling.billing.model

import com.example.usbilling.shared.Money
import com.example.usbilling.shared.UtilityId
import java.time.LocalDate

/**
 * A billing period for which consumption and charges are calculated.
 *
 * @property id Unique identifier for this billing period
 * @property utilityId The utility company this period belongs to
 * @property startDate Start of the billing period (inclusive)
 * @property endDate End of the billing period (inclusive)
 * @property billDate Date the bill is generated
 * @property dueDate Date payment is due
 * @property frequency How often bills are generated for this customer
 */
data class BillingPeriod(
    val id: String,
    val utilityId: UtilityId,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val billDate: LocalDate,
    val dueDate: LocalDate,
    val frequency: BillingFrequency
)

/**
 * How frequently bills are generated.
 */
enum class BillingFrequency {
    MONTHLY,
    BIMONTHLY,
    QUARTERLY,
    ANNUAL
}

/**
 * A single line item charge on a bill.
 *
 * @property code Charge code (e.g., "ELEC_USAGE", "CUSTOMER_CHARGE", "GAS_BASE")
 * @property description Human-readable description
 * @property amount Charge amount (positive for charges, negative for credits)
 * @property usageAmount Amount of consumption this charge is based on (null for fixed charges)
 * @property usageUnit Unit of usage (e.g., "kWh", "CCF", null for fixed charges)
 * @property rate Rate per unit (null for fixed charges)
 * @property category Category of charge for grouping on bill
 */
data class ChargeLineItem(
    val code: String,
    val description: String,
    val amount: Money,
    val usageAmount: Double? = null,
    val usageUnit: String? = null,
    val rate: Money? = null,
    val category: ChargeCategory
)

/**
 * Categories of charges for bill presentation.
 */
enum class ChargeCategory {
    /** Fixed monthly customer/service charge */
    CUSTOMER_CHARGE,
    
    /** Usage-based charges (consumption × rate) */
    USAGE_CHARGE,
    
    /** Demand/capacity charges */
    DEMAND_CHARGE,
    
    /** Regulatory surcharges and riders */
    REGULATORY_CHARGE,
    
    /** Taxes */
    TAX,
    
    /** Credits and adjustments */
    CREDIT,
    
    /** Late fees and penalties */
    FEE
}
