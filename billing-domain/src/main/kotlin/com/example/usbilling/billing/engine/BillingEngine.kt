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
        
        // Step 1: Add customer charge (fixed monthly fee)
        val customerCharge = when (val tariff = input.rateTariff) {
            is RateTariff.FlatRate -> tariff.customerCharge
            is RateTariff.TieredRate -> tariff.customerCharge
            is RateTariff.TimeOfUseRate -> tariff.customerCharge
            is RateTariff.DemandRate -> tariff.customerCharge
        }
        
        charges.add(
            ChargeLineItem(
                code = "CUSTOMER_CHARGE",
                description = "Customer Service Charge",
                amount = customerCharge,
                usageAmount = null,
                usageUnit = null,
                rate = null,
                category = ChargeCategory.CUSTOMER_CHARGE
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
        val accountBalanceAfter = input.accountBalance.applyBill(
            amount = aggregation.amountDue,
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
}

/**
 * Aggregated charge totals.
 */
data class ChargeAggregation(
    val totalCharges: Money,
    val totalCredits: Money,
    val amountDue: Money
)
