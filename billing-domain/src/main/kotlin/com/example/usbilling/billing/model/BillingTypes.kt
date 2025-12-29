package com.example.usbilling.billing.model

import com.example.usbilling.shared.BillId
import com.example.usbilling.shared.BillingCycleId
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.Money
import com.example.usbilling.shared.UtilityId
import java.time.Instant
import java.time.LocalDate

/**
 * Input data for calculating a customer's bill for a billing period.
 * Supports single-service billing (for backward compatibility).
 *
 * @property billId Unique identifier for this bill
 * @property billRunId Identifier for the bill run this bill is part of
 * @property utilityId The utility company
 * @property customerId The customer being billed
 * @property billPeriod The billing period
 * @property meterReads Meter readings for this period (start and end reads per meter)
 * @property rateTariff Rate structure to apply
 * @property accountBalance Account balance state including payment history and adjustments
 * @property demandReadings Peak demand readings for demand-based billing (optional)
 * @property regulatorySurcharges Regulatory surcharges to apply (optional)
 * @property contributions Voluntary contributions (optional)
 * @property serviceStartDate Actual start date of service within billing period (for proration)
 * @property serviceEndDate Actual end date of service within billing period (for proration)
 * @property prorationConfig Configuration for which charge types should be prorated
 */
data class BillInput(
    val billId: BillId,
    val billRunId: BillingCycleId,
    val utilityId: UtilityId,
    val customerId: CustomerId,
    val billPeriod: BillingPeriod,
    val meterReads: List<MeterReadPair>,
    val rateTariff: RateTariff,
    val accountBalance: AccountBalance,
    val demandReadings: List<DemandReading> = emptyList(),
    val regulatorySurcharges: List<RegulatorySurcharge> = emptyList(),
    val contributions: List<VoluntaryContribution> = emptyList(),
    val serviceStartDate: LocalDate? = null,
    val serviceEndDate: LocalDate? = null,
    val prorationConfig: ProrationConfig = ProrationConfig.default(),
)

/**
 * Input data for calculating a multi-service bill.
 * Used when a customer has multiple utility services (electric, water, etc.).
 *
 * @property billId Unique identifier for this bill
 * @property billRunId Identifier for the bill run this bill is part of
 * @property utilityId The utility company
 * @property customerId The customer being billed
 * @property billPeriod The billing period
 * @property serviceReads Meter readings grouped by service type
 * @property serviceTariffs Rate tariffs per service type
 * @property accountBalance Account balance state
 * @property demandReadings Peak demand readings (optional)
 * @property regulatorySurcharges Regulatory surcharges to apply
 * @property contributions Voluntary contributions
 * @property serviceStartDate Actual start date of service within billing period (for proration)
 * @property serviceEndDate Actual end date of service within billing period (for proration)
 * @property prorationConfig Configuration for which charge types should be prorated
 */
data class MultiServiceBillInput(
    val billId: BillId,
    val billRunId: BillingCycleId,
    val utilityId: UtilityId,
    val customerId: CustomerId,
    val billPeriod: BillingPeriod,
    val serviceReads: List<ServiceMeterReads>,
    val serviceTariffs: Map<ServiceType, RateTariff>,
    val accountBalance: AccountBalance,
    val demandReadings: List<DemandReading> = emptyList(),
    val regulatorySurcharges: List<RegulatorySurcharge> = emptyList(),
    val contributions: List<VoluntaryContribution> = emptyList(),
    val serviceStartDate: LocalDate? = null,
    val serviceEndDate: LocalDate? = null,
    val prorationConfig: ProrationConfig = ProrationConfig.default(),
)

/**
 * Meter readings for a specific service type.
 *
 * @property serviceType The type of service (ELECTRIC, WATER, etc.)
 * @property reads List of meter read pairs for this service
 * @property serviceStartDate Service-specific start date for proration (overrides global)
 * @property serviceEndDate Service-specific end date for proration (overrides global)
 */
data class ServiceMeterReads(
    val serviceType: ServiceType,
    val reads: List<MeterReadPair>,
    val serviceStartDate: LocalDate? = null,
    val serviceEndDate: LocalDate? = null,
)

/**
 * A pair of meter readings (start and end) for calculating consumption.
 *
 * @property meterId Meter identifier
 * @property serviceType Type of utility service
 * @property usageType Unit of usage for this meter
 * @property startRead Reading at start of period
 * @property endRead Reading at end of period
 */
data class MeterReadPair(
    val meterId: String,
    val serviceType: ServiceType,
    val usageType: UsageUnit,
    val startRead: MeterRead,
    val endRead: MeterRead,
) {
    /**
     * Calculate consumption between start and end reads.
     * Handles meter rollover if end < start.
     */
    fun calculateConsumption(): Double {
        val diff = endRead.readingValue - startRead.readingValue
        // Simple rollover detection (assumes single rollover only)
        return if (diff < 0.0) {
            // Meter rolled over - add max value
            // For electric meters this is typically 99999.9
            100_000.0 + diff
        } else {
            diff
        }
    }
}

/**
 * Result of bill calculation for a customer and period.
 *
 * @property billId Unique identifier for this bill
 * @property billRunId Identifier for the bill run
 * @property utilityId The utility company
 * @property customerId The customer
 * @property billPeriod The billing period
 * @property charges All line item charges
 * @property totalCharges Sum of all positive charges
 * @property totalCredits Sum of all negative charges (as positive number)
 * @property accountBalanceBefore Account balance before this bill
 * @property accountBalanceAfter Account balance after applying this bill
 * @property amountDue Total amount due (totalCharges - totalCredits + prior balance)
 * @property dueDate Payment due date
 * @property computedAt When this bill was calculated
 */
data class BillResult(
    val billId: BillId,
    val billRunId: BillingCycleId,
    val utilityId: UtilityId,
    val customerId: CustomerId,
    val billPeriod: BillingPeriod,
    val charges: List<ChargeLineItem>,
    val totalCharges: Money,
    val totalCredits: Money,
    val accountBalanceBefore: AccountBalance,
    val accountBalanceAfter: AccountBalance,
    val amountDue: Money,
    val dueDate: LocalDate,
    val computedAt: Instant,
) {
    /**
     * Get charges by category.
     */
    fun chargesByCategory(category: ChargeCategory): List<ChargeLineItem> = charges.filter { it.category == category }

    /**
     * Calculate total charges for a specific category.
     */
    fun totalForCategory(category: ChargeCategory): Money {
        val total = charges
            .filter { it.category == category && it.amount.amount >= 0 }
            .sumOf { it.amount.amount }
        return Money(total)
    }

    /**
     * Check if this bill includes any credits.
     */
    fun hasCredits(): Boolean = totalCredits.amount > 0

    /**
     * Check if this bill has a positive amount due.
     */
    fun hasAmountDue(): Boolean = amountDue.amount > 0

    /**
     * Format amount for display (e.g., "$75.50").
     */
    fun formatAmount(amount: Money): String {
        val dollars = amount.amount / 100
        val cents = amount.amount % 100
        return "$$dollars.${cents.toString().padStart(2, '0')}"
    }
}

/**
 * Rate tariff structure - sealed hierarchy supporting different rate types.
 */
sealed class RateTariff {
    /**
     * Simple flat rate per unit.
     */
    data class FlatRate(
        val readinessToServeCharge: Money,
        val ratePerUnit: Money,
        val unit: String,
        val regulatorySurcharges: List<RegulatoryCharge> = emptyList(),
    ) : RateTariff()

    /**
     * Tiered rate structure with progressive blocks.
     */
    data class TieredRate(
        val readinessToServeCharge: Money,
        val tiers: List<RateTier>,
        val unit: String,
        val regulatorySurcharges: List<RegulatoryCharge> = emptyList(),
    ) : RateTariff()

    /**
     * Time-of-use rate with different rates for peak/off-peak periods.
     */
    data class TimeOfUseRate(
        val readinessToServeCharge: Money,
        val peakRate: Money,
        val offPeakRate: Money,
        val shoulderRate: Money?,
        val unit: String,
        val regulatorySurcharges: List<RegulatoryCharge> = emptyList(),
    ) : RateTariff()

    /**
     * Demand rate for large commercial/industrial customers.
     * Includes both energy (usage) charges and demand (capacity) charges.
     */
    data class DemandRate(
        val readinessToServeCharge: Money,
        val energyRatePerUnit: Money,
        val demandRatePerKw: Money,
        val unit: String,
        val regulatorySurcharges: List<RegulatoryCharge> = emptyList(),
    ) : RateTariff()

    /**
     * Get all regulatory surcharges for this tariff.
     */
    fun getRegulatoryCharges(): List<RegulatoryCharge> = when (this) {
        is FlatRate -> regulatorySurcharges
        is TieredRate -> regulatorySurcharges
        is TimeOfUseRate -> regulatorySurcharges
        is DemandRate -> regulatorySurcharges
    }
}

/**
 * A single tier in a tiered rate structure.
 *
 * @property maxUsage Maximum usage for this tier (null for top tier)
 * @property ratePerUnit Rate per unit in this tier
 */
data class RateTier(
    val maxUsage: Double?,
    val ratePerUnit: Money,
)

/**
 * Regulatory surcharge or rider applied to bills.
 *
 * @property code Charge code (e.g., "PCA", "DSM", "ECA")
 * @property description Human-readable name
 * @property calculationType How this charge is calculated
 * @property rate Rate or percentage for calculation
 */
data class RegulatoryCharge(
    val code: String,
    val description: String,
    val calculationType: RegulatoryChargeType,
    val rate: Money,
)

/**
 * How a regulatory charge is calculated.
 */
enum class RegulatoryChargeType {
    /** Fixed amount per bill */
    FIXED,

    /** Percentage of energy charges */
    PERCENTAGE_OF_ENERGY,

    /** Per-unit charge ($/kWh, $/CCF, etc.) */
    PER_UNIT,

    /** Percentage of total bill */
    PERCENTAGE_OF_TOTAL,
}

/**
 * Demand reading for demand-based billing.
 * Represents peak demand (kW) during the billing period.
 *
 * @property meterId Meter identifier
 * @property peakDemandKw Peak demand in kilowatts
 * @property timestamp When peak demand occurred
 */
data class DemandReading(
    val meterId: String,
    val peakDemandKw: Double,
    val timestamp: java.time.Instant,
)

/**
 * Configuration for proration behavior when service starts or ends mid-period.
 *
 * @property prorateReadinessToServe Whether to prorate fixed readiness-to-serve charges
 * @property prorateFixedRegulatoryCharges Whether to prorate FIXED regulatory surcharges
 * @property prorateContributions Whether to prorate voluntary contributions
 */
data class ProrationConfig(
    val prorateReadinessToServe: Boolean = true,
    val prorateFixedRegulatoryCharges: Boolean = true,
    val prorateContributions: Boolean = false,
) {
    companion object {
        /**
         * Default proration config: prorate readiness-to-serve and fixed regulatory charges,
         * but not voluntary contributions (customer opted in for full amount).
         */
        fun default() = ProrationConfig()

        /**
         * No proration - bill full amounts for everything.
         */
        fun none() = ProrationConfig(
            prorateReadinessToServe = false,
            prorateFixedRegulatoryCharges = false,
            prorateContributions = false,
        )

        /**
         * Full proration - prorate all fixed charges including contributions.
         */
        fun full() = ProrationConfig(
            prorateReadinessToServe = true,
            prorateFixedRegulatoryCharges = true,
            prorateContributions = true,
        )
    }
}
