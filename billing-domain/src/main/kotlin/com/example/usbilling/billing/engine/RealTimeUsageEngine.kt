package com.example.usbilling.billing.engine

import com.example.usbilling.billing.model.*
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.Money
import com.example.usbilling.shared.UtilityId
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Engine for calculating real-time usage snapshots and projected bills.
 *
 * Provides mid-cycle usage reporting for customer portals and mobile apps,
 * separate from the periodic billing engine which generates final bills.
 */
object RealTimeUsageEngine {

    /**
     * Generate a real-time usage snapshot for a single service.
     *
     * @param utilityId The utility company
     * @param customerId The customer
     * @param serviceType Type of service (ELECTRIC, WATER, etc.)
     * @param currentPeriod Active billing period
     * @param dailyUsageHistory Recent daily usage data
     * @param currentMeterRead Most recent meter reading
     * @param periodStartMeterRead Meter reading at start of billing period
     * @param tariff Rate structure to apply
     * @param snapshotTime When to generate snapshot (defaults to now)
     * @return Real-time usage snapshot with projections
     */
    fun generateSnapshot(
        utilityId: UtilityId,
        customerId: CustomerId,
        serviceType: ServiceType,
        currentPeriod: BillingPeriod,
        dailyUsageHistory: List<DailyUsage>,
        currentMeterRead: MeterRead?,
        periodStartMeterRead: MeterRead?,
        tariff: RateTariff,
        snapshotTime: Instant = Instant.now(),
    ): RealTimeUsageSnapshot {
        // Calculate period-to-date usage
        val periodToDate = calculatePeriodToDate(
            currentPeriod = currentPeriod,
            currentMeterRead = currentMeterRead,
            periodStartMeterRead = periodStartMeterRead,
            dailyUsageHistory = dailyUsageHistory,
            tariff = tariff,
            snapshotTime = snapshotTime,
        )

        // Project bill to end of period
        val projectedBill = projectBill(
            periodToDate = periodToDate,
            dailyUsageHistory = dailyUsageHistory,
            currentPeriod = currentPeriod,
            tariff = tariff,
            snapshotTime = snapshotTime,
        )

        return RealTimeUsageSnapshot(
            utilityId = utilityId,
            customerId = customerId,
            serviceType = serviceType,
            snapshotTime = snapshotTime,
            currentPeriod = currentPeriod,
            periodToDate = periodToDate,
            recentUsage = dailyUsageHistory.takeLast(30), // Last 30 days
            projectedBill = projectedBill,
        )
    }

    /**
     * Generate multi-service usage dashboard.
     *
     * @param utilityId The utility company
     * @param customerId The customer
     * @param serviceSnapshots Individual service snapshots
     * @param budgetAmount Optional budget billing amount
     * @param snapshotTime When to generate dashboard
     * @return Multi-service dashboard
     */
    fun generateDashboard(
        utilityId: UtilityId,
        customerId: CustomerId,
        serviceSnapshots: List<RealTimeUsageSnapshot>,
        budgetAmount: Money? = null,
        snapshotTime: Instant = Instant.now(),
    ): MultiServiceUsageDashboard {
        val totalProjected = serviceSnapshots
            .sumOf { it.projectedBill.projectedTotal.amount }
            .let { Money(it) }

        val budgetStatus = budgetAmount?.let {
            val variance = Money(it.amount - totalProjected.amount)
            BudgetStatus(
                budgetAmount = it,
                projectedAmount = totalProjected,
                variance = variance,
                onTrack = variance.amount >= 0,
            )
        }

        return MultiServiceUsageDashboard(
            utilityId = utilityId,
            customerId = customerId,
            snapshotTime = snapshotTime,
            serviceSnapshots = serviceSnapshots,
            totalProjectedBill = totalProjected,
            budgetStatus = budgetStatus,
        )
    }

    private fun calculatePeriodToDate(
        currentPeriod: BillingPeriod,
        currentMeterRead: MeterRead?,
        periodStartMeterRead: MeterRead?,
        dailyUsageHistory: List<DailyUsage>,
        tariff: RateTariff,
        snapshotTime: Instant,
    ): PeriodToDateUsage {
        val today = LocalDate.ofInstant(snapshotTime, java.time.ZoneId.systemDefault())
        val daysElapsed = ChronoUnit.DAYS.between(currentPeriod.startDate, today).toInt() + 1
        val daysRemaining = ChronoUnit.DAYS.between(today, currentPeriod.endDate).toInt()

        // Calculate usage to date from meter reads or daily history
        val usageToDate = if (currentMeterRead != null && periodStartMeterRead != null) {
            currentMeterRead.readingValue - periodStartMeterRead.readingValue
        } else {
            dailyUsageHistory
                .filter { it.date >= currentPeriod.startDate && it.date <= today }
                .sumOf { it.usage }
        }

        // Estimate charges based on usage so far
        val estimatedCharges = estimateCharges(
            usage = usageToDate,
            tariff = tariff,
            daysElapsed = daysElapsed,
            totalDaysInPeriod = currentPeriod.daysInPeriod(),
        )

        val usageUnit = currentMeterRead?.usageUnit
            ?: dailyUsageHistory.firstOrNull()?.usageUnit
            ?: UsageUnit.KWH

        return PeriodToDateUsage(
            daysElapsed = daysElapsed,
            daysRemaining = daysRemaining.coerceAtLeast(0),
            usageToDate = usageToDate,
            usageUnit = usageUnit,
            lastMeterRead = currentMeterRead,
            estimatedCharges = estimatedCharges,
        )
    }

    private fun projectBill(
        periodToDate: PeriodToDateUsage,
        dailyUsageHistory: List<DailyUsage>,
        currentPeriod: BillingPeriod,
        tariff: RateTariff,
        snapshotTime: Instant,
    ): ProjectedBill {
        // Use daily average projection for simplicity
        val avgDailyUsage = if (periodToDate.daysElapsed > 0) {
            periodToDate.usageToDate / periodToDate.daysElapsed
        } else {
            0.0
        }

        val projectedUsage = periodToDate.usageToDate + (avgDailyUsage * periodToDate.daysRemaining)

        // Estimate total charges including readiness-to-serve
        val projectedTotal = estimateCharges(
            usage = projectedUsage,
            tariff = tariff,
            daysElapsed = currentPeriod.daysInPeriod(),
            totalDaysInPeriod = currentPeriod.daysInPeriod(),
        )

        // Calculate confidence based on days elapsed
        val confidence = (periodToDate.daysElapsed.toDouble() / currentPeriod.daysInPeriod()).coerceIn(0.0, 1.0)

        // Create breakdown
        val breakdown = createProjectionBreakdown(tariff, projectedUsage, projectedTotal)

        return ProjectedBill(
            projectedTotal = projectedTotal,
            projectedUsage = projectedUsage,
            usageUnit = periodToDate.usageUnit,
            projectionMethod = ProjectionMethod.DAILY_AVERAGE,
            confidenceLevel = confidence,
            breakdown = breakdown,
        )
    }

    private fun estimateCharges(
        usage: Double,
        tariff: RateTariff,
        daysElapsed: Int,
        totalDaysInPeriod: Int,
    ): Money {
        // Get readiness-to-serve charge (prorated if mid-period)
        val readinessToServe = when (tariff) {
            is RateTariff.FlatRate -> tariff.readinessToServeCharge
            is RateTariff.TieredRate -> tariff.readinessToServeCharge
            is RateTariff.TimeOfUseRate -> tariff.readinessToServeCharge
            is RateTariff.DemandRate -> tariff.readinessToServeCharge
        }

        val prorationFactor = daysElapsed.toDouble() / totalDaysInPeriod
        val proratedReadinessToServe = Money((readinessToServe.amount * prorationFactor).toLong())

        // Calculate usage charges
        val usageCharges = when (tariff) {
            is RateTariff.FlatRate -> {
                Money((usage * tariff.ratePerUnit.amount).toLong())
            }
            is RateTariff.TieredRate -> {
                calculateTieredCharges(usage, tariff.tiers)
            }
            is RateTariff.TimeOfUseRate -> {
                // Simplified: assume 50/50 peak/off-peak
                val peakUsage = usage * 0.5
                val offPeakUsage = usage * 0.5
                Money(
                    (peakUsage * tariff.peakRate.amount + offPeakUsage * tariff.offPeakRate.amount).toLong(),
                )
            }
            is RateTariff.DemandRate -> {
                Money((usage * tariff.energyRatePerUnit.amount).toLong())
            }
        }

        return Money(proratedReadinessToServe.amount + usageCharges.amount)
    }

    private fun calculateTieredCharges(usage: Double, tiers: List<RateTier>): Money {
        var remainingUsage = usage
        var totalCharge = 0L

        for (tier in tiers) {
            val tierMax = tier.maxUsage ?: Double.MAX_VALUE
            val tierUsage = minOf(remainingUsage, tierMax)

            if (tierUsage > 0) {
                totalCharge += (tierUsage * tier.ratePerUnit.amount).toLong()
                remainingUsage -= tierUsage
            }

            if (remainingUsage <= 0) break
        }

        return Money(totalCharge)
    }

    private fun createProjectionBreakdown(
        tariff: RateTariff,
        projectedUsage: Double,
        projectedTotal: Money,
    ): List<ProjectedCharge> {
        val breakdown = mutableListOf<ProjectedCharge>()

        // Readiness to serve
        val readinessToServe = when (tariff) {
            is RateTariff.FlatRate -> tariff.readinessToServeCharge
            is RateTariff.TieredRate -> tariff.readinessToServeCharge
            is RateTariff.TimeOfUseRate -> tariff.readinessToServeCharge
            is RateTariff.DemandRate -> tariff.readinessToServeCharge
        }

        breakdown.add(
            ProjectedCharge(
                description = "Readiness to Serve",
                projectedAmount = readinessToServe,
                category = ChargeCategory.READINESS_TO_SERVE,
            ),
        )

        // Usage charges
        val usageCharge = Money(projectedTotal.amount - readinessToServe.amount)
        breakdown.add(
            ProjectedCharge(
                description = "Usage Charges",
                projectedAmount = usageCharge,
                category = ChargeCategory.USAGE_CHARGE,
            ),
        )

        return breakdown
    }
}
