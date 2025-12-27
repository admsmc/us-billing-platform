package com.example.usbilling.billing.model

import com.example.usbilling.shared.BillId
import com.example.usbilling.shared.BillRunId
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.Money
import com.example.usbilling.shared.UtilityId
import java.time.Instant
import java.time.LocalDate

/**
 * Input data for calculating a customer's bill for a billing period.
 *
 * @property billId Unique identifier for this bill
 * @property billRunId Identifier for the bill run this bill is part of
 * @property utilityId The utility company
 * @property customerId The customer being billed
 * @property billPeriod The billing period
 * @property meterReads Meter readings for this period (start and end reads per meter)
 * @property rateTariff Rate structure to apply
 * @property priorBalance Outstanding balance from previous bills
 */
data class BillInput(
    val billId: BillId,
    val billRunId: BillRunId,
    val utilityId: UtilityId,
    val customerId: CustomerId,
    val billPeriod: BillingPeriod,
    val meterReads: List<MeterReadPair>,
    val rateTariff: RateTariff,
    val priorBalance: Money
)

/**
 * A pair of meter readings (start and end) for calculating consumption.
 *
 * @property meterId Meter identifier
 * @property usageType Type of utility
 * @property startRead Reading at start of period
 * @property endRead Reading at end of period
 */
data class MeterReadPair(
    val meterId: String,
    val usageType: UsageType,
    val startRead: MeterRead,
    val endRead: MeterRead
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
 * @property priorBalance Balance carried forward from previous bills
 * @property amountDue Total amount due (totalCharges - totalCredits + priorBalance)
 * @property dueDate Payment due date
 * @property computedAt When this bill was calculated
 */
data class BillResult(
    val billId: BillId,
    val billRunId: BillRunId,
    val utilityId: UtilityId,
    val customerId: CustomerId,
    val billPeriod: BillingPeriod,
    val charges: List<ChargeLineItem>,
    val totalCharges: Money,
    val totalCredits: Money,
    val priorBalance: Money,
    val amountDue: Money,
    val dueDate: LocalDate,
    val computedAt: Instant
)

/**
 * Rate tariff structure - sealed hierarchy supporting different rate types.
 */
sealed class RateTariff {
    /**
     * Simple flat rate per unit.
     */
    data class FlatRate(
        val customerCharge: Money,
        val ratePerUnit: Money,
        val unit: String
    ) : RateTariff()
    
    /**
     * Tiered rate structure with progressive blocks.
     */
    data class TieredRate(
        val customerCharge: Money,
        val tiers: List<RateTier>,
        val unit: String
    ) : RateTariff()
    
    /**
     * Time-of-use rate with different rates for peak/off-peak periods.
     */
    data class TimeOfUseRate(
        val customerCharge: Money,
        val peakRate: Money,
        val offPeakRate: Money,
        val shoulderRate: Money?,
        val unit: String
    ) : RateTariff()
}

/**
 * A single tier in a tiered rate structure.
 *
 * @property maxUsage Maximum usage for this tier (null for top tier)
 * @property ratePerUnit Rate per unit in this tier
 */
data class RateTier(
    val maxUsage: Double?,
    val ratePerUnit: Money
)
