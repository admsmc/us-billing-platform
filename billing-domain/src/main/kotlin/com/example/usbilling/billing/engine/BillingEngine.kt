package com.example.usbilling.billing.engine

import com.example.usbilling.billing.model.*
import com.example.usbilling.shared.Money
import java.time.Instant

/**
 * Main billing calculation engine.
 *
 * Orchestrates the calculation of a customer bill by:
 * 1. Calculating consumption from meter reads
 * 2. Applying rate tariffs to consumption
 * 3. Aggregating all charges
 * 4. Computing final amount due
 */
object BillingEngine {

    /**
     * Calculate a complete bill for a customer and billing period.
     *
     * @param input All input data needed for bill calculation
     * @param computedAt Timestamp when calculation was performed
     * @return Complete bill with all charges and totals
     */
    fun calculateBill(input: BillInput, computedAt: Instant = Instant.now()): BillResult {
        val charges = mutableListOf<ChargeLineItem>()

        // Calculate proration factor if service dates are provided
        val prorationFactor = if (input.serviceStartDate != null || input.serviceEndDate != null) {
            val effectiveStart = input.serviceStartDate ?: input.billPeriod.startDate
            val effectiveEnd = input.serviceEndDate ?: input.billPeriod.endDate
            input.billPeriod.prorationFactor(effectiveStart, effectiveEnd)
        } else {
            1.0
        }

        // Step 1: Add readiness-to-serve charge (fixed monthly fee)
        val baseReadinessToServeCharge = when (val tariff = input.rateTariff) {
            is RateTariff.FlatRate -> tariff.readinessToServeCharge
            is RateTariff.TieredRate -> tariff.readinessToServeCharge
            is RateTariff.TimeOfUseRate -> tariff.readinessToServeCharge
            is RateTariff.DemandRate -> tariff.readinessToServeCharge
        }

        // Apply proration to readiness-to-serve charge if configured
        val readinessToServeCharge = if (input.prorationConfig.prorateReadinessToServe && prorationFactor < 1.0) {
            Money((baseReadinessToServeCharge.amount * prorationFactor).toLong())
        } else {
            baseReadinessToServeCharge
        }

        val description = if (prorationFactor < 1.0 && input.prorationConfig.prorateReadinessToServe) {
            "Readiness to Serve Charge (prorated)"
        } else {
            "Readiness to Serve Charge"
        }

        charges.add(
            ChargeLineItem(
                code = "READINESS_TO_SERVE",
                description = description,
                amount = readinessToServeCharge,
                usageAmount = null,
                usageUnit = null,
                rate = null,
                category = ChargeCategory.READINESS_TO_SERVE,
            ),
        )

        // Step 2: Calculate usage charges for each meter
        for (meterReadPair in input.meterReads) {
            val consumption = meterReadPair.calculateConsumption()

            // Find corresponding demand reading if applicable
            val demandKw = input.demandReadings
                .find { it.meterId == meterReadPair.meterId }
                ?.peakDemandKw

            val usageCharges = RateApplier.applyRate(
                consumption = consumption,
                tariff = input.rateTariff,
                usageUnit = meterReadPair.usageType,
                serviceType = meterReadPair.serviceType,
                demandKw = demandKw,
            )
            charges.addAll(usageCharges)
        }

        // Step 3: Aggregate totals
        val aggregation = ChargeAggregator.aggregate(charges, input.accountBalance)

        // Step 4: Update account balance with this bill
        // Note: applyBill adds to existing balance, so pass new charges only (not amountDue)
        val newChargesAmount = Money(aggregation.totalCharges.amount - aggregation.totalCredits.amount)
        val accountBalanceAfter = input.accountBalance.applyBill(
            amount = newChargesAmount,
            billDate = input.billPeriod.billDate,
        )

        return BillResult(
            billId = input.billId,
            billRunId = input.billRunId,
            utilityId = input.utilityId,
            customerId = input.customerId,
            billPeriod = input.billPeriod,
            charges = charges,
            totalCharges = aggregation.totalCharges,
            totalCredits = aggregation.totalCredits,
            accountBalanceBefore = input.accountBalance,
            accountBalanceAfter = accountBalanceAfter,
            amountDue = aggregation.amountDue,
            dueDate = input.billPeriod.dueDate,
            computedAt = computedAt,
        )
    }

    /**
     * Calculate a multi-service bill (electric, water, wastewater, broadband, etc.).
     *
     * @param input All input data for multi-service billing
     * @param computedAt Timestamp when calculation was performed
     * @return Complete bill with charges from all services
     */
    fun calculateMultiServiceBill(input: MultiServiceBillInput, computedAt: Instant = Instant.now()): BillResult {
        val charges = mutableListOf<ChargeLineItem>()

        // Calculate global proration factor if service dates are provided
        val globalProrationFactor = if (input.serviceStartDate != null || input.serviceEndDate != null) {
            val effectiveStart = input.serviceStartDate ?: input.billPeriod.startDate
            val effectiveEnd = input.serviceEndDate ?: input.billPeriod.endDate
            input.billPeriod.prorationFactor(effectiveStart, effectiveEnd)
        } else {
            1.0
        }

        // Step 1: Process each service type
        for (serviceReads in input.serviceReads) {
            val serviceTariff = input.serviceTariffs[serviceReads.serviceType]
                ?: error("No tariff configured for ${serviceReads.serviceType}")

            // Calculate service-specific proration factor (overrides global if provided)
            val serviceProrationFactor = if (serviceReads.serviceStartDate != null || serviceReads.serviceEndDate != null) {
                val effectiveStart = serviceReads.serviceStartDate ?: input.billPeriod.startDate
                val effectiveEnd = serviceReads.serviceEndDate ?: input.billPeriod.endDate
                input.billPeriod.prorationFactor(effectiveStart, effectiveEnd)
            } else {
                globalProrationFactor
            }

            // Add readiness-to-serve charge per service
            val baseReadinessToServeCharge = when (serviceTariff) {
                is RateTariff.FlatRate -> serviceTariff.readinessToServeCharge
                is RateTariff.TieredRate -> serviceTariff.readinessToServeCharge
                is RateTariff.TimeOfUseRate -> serviceTariff.readinessToServeCharge
                is RateTariff.DemandRate -> serviceTariff.readinessToServeCharge
            }

            // Apply proration to readiness-to-serve charge if configured
            val readinessToServeCharge = if (input.prorationConfig.prorateReadinessToServe && serviceProrationFactor < 1.0) {
                Money((baseReadinessToServeCharge.amount * serviceProrationFactor).toLong())
            } else {
                baseReadinessToServeCharge
            }

            val description = if (serviceProrationFactor < 1.0 && input.prorationConfig.prorateReadinessToServe) {
                "${serviceReads.serviceType.displayName()} Readiness to Serve (prorated)"
            } else {
                "${serviceReads.serviceType.displayName()} Readiness to Serve"
            }

            charges.add(
                ChargeLineItem(
                    code = "${serviceReads.serviceType}_READINESS_TO_SERVE",
                    description = description,
                    amount = readinessToServeCharge,
                    usageAmount = null,
                    usageUnit = null,
                    rate = null,
                    category = ChargeCategory.READINESS_TO_SERVE,
                    serviceType = serviceReads.serviceType,
                ),
            )

            // Calculate usage charges for this service
            for (meterReadPair in serviceReads.reads) {
                val consumption = meterReadPair.calculateConsumption()

                // Find corresponding demand reading if applicable
                val demandKw = input.demandReadings
                    .find { it.meterId == meterReadPair.meterId }
                    ?.peakDemandKw

                val usageCharges = RateApplier.applyRate(
                    consumption = consumption,
                    tariff = serviceTariff,
                    usageUnit = meterReadPair.usageType,
                    serviceType = serviceReads.serviceType,
                    demandKw = demandKw,
                )
                charges.addAll(usageCharges)
            }
        }

        // Step 2: Apply regulatory surcharges per service
        for (surcharge in input.regulatorySurcharges) {
            val surchargeCharges = applySurcharges(
                surcharge = surcharge,
                existingCharges = charges,
                prorationFactor = globalProrationFactor,
                prorationConfig = input.prorationConfig,
            )
            charges.addAll(surchargeCharges)
        }

        // Step 3: Add voluntary contributions
        for (contribution in input.contributions) {
            // Apply proration to contributions if configured
            val contributionAmount = if (input.prorationConfig.prorateContributions && globalProrationFactor < 1.0) {
                Money((contribution.amount.amount * globalProrationFactor).toLong())
            } else {
                contribution.amount
            }

            val description = if (input.prorationConfig.prorateContributions && globalProrationFactor < 1.0) {
                "${contribution.description} (prorated)"
            } else {
                contribution.description
            }

            charges.add(
                ChargeLineItem(
                    code = contribution.code,
                    description = description,
                    amount = contributionAmount,
                    usageAmount = null,
                    usageUnit = null,
                    rate = null,
                    category = ChargeCategory.CONTRIBUTION,
                    serviceType = ServiceType.DONATION,
                ),
            )
        }

        // Step 4: Aggregate totals
        val aggregation = ChargeAggregator.aggregate(charges, input.accountBalance)

        // Step 5: Update account balance with this bill
        // Note: applyBill adds to existing balance, so pass new charges only (not amountDue)
        val newChargesAmount = Money(aggregation.totalCharges.amount - aggregation.totalCredits.amount)
        val accountBalanceAfter = input.accountBalance.applyBill(
            amount = newChargesAmount,
            billDate = input.billPeriod.billDate,
        )

        return BillResult(
            billId = input.billId,
            billRunId = input.billRunId,
            utilityId = input.utilityId,
            customerId = input.customerId,
            billPeriod = input.billPeriod,
            charges = charges,
            totalCharges = aggregation.totalCharges,
            totalCredits = aggregation.totalCredits,
            accountBalanceBefore = input.accountBalance,
            accountBalanceAfter = accountBalanceAfter,
            amountDue = aggregation.amountDue,
            dueDate = input.billPeriod.dueDate,
            computedAt = computedAt,
        )
    }

    /**
     * Apply a regulatory surcharge to existing charges.
     */
    private fun applySurcharges(
        surcharge: RegulatorySurcharge,
        existingCharges: List<ChargeLineItem>,
        prorationFactor: Double = 1.0,
        prorationConfig: ProrationConfig = ProrationConfig.default(),
    ): List<ChargeLineItem> {
        val result = mutableListOf<ChargeLineItem>()

        // Group charges by service type for service-specific surcharges
        val chargesByService = existingCharges.groupBy { it.serviceType }

        for ((serviceType, serviceCharges) in chargesByService) {
            // Skip if surcharge doesn't apply to this service
            if (serviceType != null && !surcharge.appliesTo(serviceType)) {
                continue
            }

            val baseAmount = when (surcharge.calculationType) {
                RegulatorySurchargeCalculation.FIXED -> {
                    surcharge.fixedAmount!!
                }

                RegulatorySurchargeCalculation.PER_UNIT -> {
                    val totalUsage = serviceCharges
                        .filter { it.category == ChargeCategory.USAGE_CHARGE && it.usageAmount != null }
                        .sumOf { it.usageAmount!! }
                    Money((totalUsage * surcharge.ratePerUnit!!.amount).toLong())
                }

                RegulatorySurchargeCalculation.PERCENTAGE_OF_ENERGY -> {
                    val energyTotal = serviceCharges
                        .filter { it.category == ChargeCategory.USAGE_CHARGE }
                        .sumOf { it.amount.amount }
                    Money(((energyTotal * surcharge.percentageRate!!) / 100.0).toLong())
                }

                RegulatorySurchargeCalculation.PERCENTAGE_OF_TOTAL -> {
                    val serviceTotal = serviceCharges.sumOf { it.amount.amount }
                    Money(((serviceTotal * surcharge.percentageRate!!) / 100.0).toLong())
                }
            }

            // Apply proration to FIXED surcharges if configured
            val amount = if (surcharge.calculationType == RegulatorySurchargeCalculation.FIXED &&
                prorationConfig.prorateFixedRegulatoryCharges &&
                prorationFactor < 1.0
            ) {
                Money((baseAmount.amount * prorationFactor).toLong())
            } else {
                baseAmount
            }

            if (amount.amount > 0) {
                result.add(
                    ChargeLineItem(
                        code = surcharge.code,
                        description = surcharge.description,
                        amount = amount,
                        usageAmount = null,
                        usageUnit = null,
                        rate = null,
                        category = ChargeCategory.REGULATORY_CHARGE,
                        serviceType = serviceType,
                    ),
                )
            }
        }

        return result
    }
}
