package com.example.usbilling.billing.model

import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.Money
import com.example.usbilling.shared.UtilityId
import java.time.Instant
import java.time.LocalDate

/**
 * Real-time usage snapshot showing current consumption and estimated charges.
 * Used for customer portals, mobile apps, and in-home displays.
 *
 * @property utilityId The utility company
 * @property customerId The customer
 * @property serviceType Type of service (ELECTRIC, WATER, etc.)
 * @property snapshotTime When this snapshot was generated
 * @property currentPeriod The active billing period
 * @property periodToDate Usage and charges accumulated in current billing period
 * @property recentUsage Recent usage history for trend analysis
 * @property projectedBill Estimated bill based on current usage patterns
 */
data class RealTimeUsageSnapshot(
    val utilityId: UtilityId,
    val customerId: CustomerId,
    val serviceType: ServiceType,
    val snapshotTime: Instant,
    val currentPeriod: BillingPeriod,
    val periodToDate: PeriodToDateUsage,
    val recentUsage: List<DailyUsage>,
    val projectedBill: ProjectedBill
)

/**
 * Period-to-date usage and charges within the current billing cycle.
 *
 * @property daysElapsed Number of days elapsed in current billing period
 * @property daysRemaining Number of days remaining in current billing period
 * @property usageToDate Total consumption so far this period
 * @property usageUnit Unit of measurement
 * @property lastMeterRead Most recent meter reading
 * @property estimatedCharges Estimated charges based on usage so far
 */
data class PeriodToDateUsage(
    val daysElapsed: Int,
    val daysRemaining: Int,
    val usageToDate: Double,
    val usageUnit: UsageUnit,
    val lastMeterRead: MeterRead?,
    val estimatedCharges: Money
)

/**
 * Daily usage aggregation for trend analysis.
 *
 * @property date The day this usage occurred
 * @property usage Consumption amount for this day
 * @property usageUnit Unit of measurement
 * @property cost Estimated cost for this day's usage
 * @property temperatureHigh High temperature for the day (for correlation analysis)
 * @property temperatureLow Low temperature for the day
 */
data class DailyUsage(
    val date: LocalDate,
    val usage: Double,
    val usageUnit: UsageUnit,
    val cost: Money,
    val temperatureHigh: Double? = null,
    val temperatureLow: Double? = null
)

/**
 * Projected bill estimate based on current usage patterns.
 *
 * @property projectedTotal Estimated total bill amount
 * @property projectedUsage Estimated total usage by end of period
 * @property usageUnit Unit of measurement
 * @property projectionMethod How the projection was calculated
 * @property confidenceLevel Confidence in projection (0.0 to 1.0)
 * @property breakdown Breakdown of projected charges by category
 */
data class ProjectedBill(
    val projectedTotal: Money,
    val projectedUsage: Double,
    val usageUnit: UsageUnit,
    val projectionMethod: ProjectionMethod,
    val confidenceLevel: Double,
    val breakdown: List<ProjectedCharge>
)

/**
 * How the bill projection was calculated.
 */
enum class ProjectionMethod {
    /** Simple daily average × remaining days */
    DAILY_AVERAGE,
    
    /** Weighted average giving more weight to recent days */
    WEIGHTED_AVERAGE,
    
    /** Historical pattern from same period last year */
    YEAR_OVER_YEAR,
    
    /** Machine learning model prediction */
    ML_MODEL,
    
    /** Degree-day adjusted for weather correlation */
    DEGREE_DAY_ADJUSTED
}

/**
 * A single projected charge line item.
 *
 * @property description Charge description
 * @property projectedAmount Estimated charge amount
 * @property category Charge category
 */
data class ProjectedCharge(
    val description: String,
    val projectedAmount: Money,
    val category: ChargeCategory
)

/**
 * Interval usage data for high-resolution (15-min, hourly) reporting.
 * Used for time-of-use analysis and detailed consumption patterns.
 *
 * @property meterId Meter identifier
 * @property serviceType Service type
 * @property intervalStart Start of interval
 * @property intervalEnd End of interval
 * @property usage Consumption during this interval
 * @property usageUnit Unit of measurement
 * @property demand Peak demand during interval (for electric service)
 * @property cost Estimated cost for this interval
 */
data class IntervalUsage(
    val meterId: String,
    val serviceType: ServiceType,
    val intervalStart: Instant,
    val intervalEnd: Instant,
    val usage: Double,
    val usageUnit: UsageUnit,
    val demand: Double? = null,
    val cost: Money
)

/**
 * Multi-service real-time usage dashboard.
 * Aggregates real-time data across all services for a customer.
 *
 * @property utilityId The utility company
 * @property customerId The customer
 * @property snapshotTime When this dashboard was generated
 * @property serviceSnapshots Real-time snapshot for each service
 * @property totalProjectedBill Combined projected bill across all services
 * @property budgetStatus Budget tracking status (if customer has budget plan)
 */
data class MultiServiceUsageDashboard(
    val utilityId: UtilityId,
    val customerId: CustomerId,
    val snapshotTime: Instant,
    val serviceSnapshots: List<RealTimeUsageSnapshot>,
    val totalProjectedBill: Money,
    val budgetStatus: BudgetStatus? = null
)

/**
 * Budget plan status for customers on budget billing.
 *
 * @property budgetAmount Monthly budget amount
 * @property projectedAmount Projected amount for current period
 * @property variance Difference between budget and projection
 * @property onTrack Whether customer is on track with budget
 */
data class BudgetStatus(
    val budgetAmount: Money,
    val projectedAmount: Money,
    val variance: Money,
    val onTrack: Boolean
)
