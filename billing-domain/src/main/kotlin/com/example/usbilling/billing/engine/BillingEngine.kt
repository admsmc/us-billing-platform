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
        
        // Step 1: Add readiness-to-serve charge (fixed monthly fee)
        val readinessToServeCharge = when (val tariff = input.rateTariff) {
            is RateTariff.FlatRate -> tariff.readinessToServeCharge
            is RateTariff.TieredRate -> tariff.readinessToServeCharge
            is RateTariff.TimeOfUseRate -> tariff.readinessToServeCharge
            is RateTariff.DemandRate -> tariff.readinessToServeCharge
        }
        
        charges.add(
            ChargeLineItem(
                code = "READINESS_TO_SERVE",
                description = "Readiness to Serve Charge",
                amount = readinessToServeCharge,
                usageAmount = null,
                usageUnit = null,
                rate = null,
                category = ChargeCategory.READINESS_TO_SERVE
            )
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
                demandKw = demandKw
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
            billDate = input.billPeriod.billDate
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
            computedAt = computedAt
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
        
        // Step 1: Process each service type
        for (serviceReads in input.serviceReads) {
            val serviceTariff = input.serviceTariffs[serviceReads.serviceType]
                ?: error("No tariff configured for ${serviceReads.serviceType}")
            
            // Add readiness-to-serve charge per service
            val readinessToServeCharge = when (serviceTariff) {
                is RateTariff.FlatRate -> serviceTariff.readinessToServeCharge
                is RateTariff.TieredRate -> serviceTariff.readinessToServeCharge
                is RateTariff.TimeOfUseRate -> serviceTariff.readinessToServeCharge
                is RateTariff.DemandRate -> serviceTariff.readinessToServeCharge
            }
            
            charges.add(
                ChargeLineItem(
                    code = "${serviceReads.serviceType}_READINESS_TO_SERVE",
                    description = "${serviceReads.serviceType.displayName()} Readiness to Serve",
                    amount = readinessToServeCharge,
                    usageAmount = null,
                    usageUnit = null,
                    rate = null,
                    category = ChargeCategory.READINESS_TO_SERVE,
                    serviceType = serviceReads.serviceType
                )
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
                    demandKw = demandKw
                )
                charges.addAll(usageCharges)
            }
        }
        
        // Step 2: Apply regulatory surcharges per service
        for (surcharge in input.regulatorySurcharges) {
            val surchargeCharges = applySurcharges(
                surcharge = surcharge,
                existingCharges = charges
            )
            charges.addAll(surchargeCharges)
        }
        
        // Step 3: Add voluntary contributions
        for (contribution in input.contributions) {
            charges.add(
                ChargeLineItem(
                    code = contribution.code,
                    description = contribution.description,
                    amount = contribution.amount,
                    usageAmount = null,
                    usageUnit = null,
                    rate = null,
                    category = ChargeCategory.CONTRIBUTION,
                    serviceType = ServiceType.DONATION
                )
            )
        }
        
        // Step 4: Aggregate totals
        val aggregation = ChargeAggregator.aggregate(charges, input.accountBalance)
        
        // Step 5: Update account balance with this bill
        // Note: applyBill adds to existing balance, so pass new charges only (not amountDue)
        val newChargesAmount = Money(aggregation.totalCharges.amount - aggregation.totalCredits.amount)
        val accountBalanceAfter = input.accountBalance.applyBill(
            amount = newChargesAmount,
            billDate = input.billPeriod.billDate
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
            computedAt = computedAt
        )
    }
    
    /**
     * Apply a regulatory surcharge to existing charges.
     */
    private fun applySurcharges(
        surcharge: RegulatorySurcharge,
        existingCharges: List<ChargeLineItem>
    ): List<ChargeLineItem> {
        val result = mutableListOf<ChargeLineItem>()
        
        // Group charges by service type for service-specific surcharges
        val chargesByService = existingCharges.groupBy { it.serviceType }
        
        for ((serviceType, serviceCharges) in chargesByService) {
            // Skip if surcharge doesn't apply to this service
            if (serviceType != null && !surcharge.appliesTo(serviceType)) {
                continue
            }
            
            val amount = when (surcharge.calculationType) {
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
                        serviceType = serviceType
                    )
                )
            }
        }
        
        return result
    }
}

/**
 * Aggregated charge totals.
 */
data class ChargeAggregation(
    val totalCharges: Money,
    val totalCredits: Money,
    val amountDue: Money
)
