package com.example.usbilling.worker.http

import com.example.usbilling.billing.engine.BillingEngine
import com.example.usbilling.billing.model.*
import com.example.usbilling.shared.BillId
import com.example.usbilling.shared.BillingCycleId
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.Money
import com.example.usbilling.shared.UtilityId
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate

/**
 * Demo endpoint for testing the new BillingEngine.
 *
 * This endpoint demonstrates utility billing (electric service) using the billing-domain
 * instead of the legacy payroll-domain.
 *
 * @see BillingEngine
 */
@RestController
class DemoUtilityBillingController {

    /**
     * Generate a sample utility bill for a demo customer.
     *
     * Returns a complete bill calculated by BillingEngine with:
     * - Electric usage charge (tiered rate)
     * - Readiness-to-serve (fixed monthly charge)
     * - Account balance tracking
     *
     * Example: GET /demo-utility-bill
     */
    @GetMapping("/demo-utility-bill")
    fun demoUtilityBill(): Map<String, Any> {
        // Sample: Residential electric customer with 750 kWh usage
        val billInput = createSampleElectricBill()
        
        val result = BillingEngine.calculateBill(
            input = billInput,
            computedAt = Instant.now()
        )
        
        return mapOf(
            "message" to "Demo bill calculated using BillingEngine (billing-domain)",
            "utilityId" to result.utilityId.value,
            "customerId" to result.customerId.value,
            "billId" to result.billId.value,
            "billPeriod" to mapOf(
                "start" to result.billPeriod.startDate.toString(),
                "end" to result.billPeriod.endDate.toString(),
                "billDate" to result.billPeriod.billDate.toString(),
                "dueDate" to result.dueDate.toString()
            ),
            "charges" to result.charges.map { charge ->
                mapOf(
                    "code" to charge.code,
                    "description" to charge.description,
                    "amount" to result.formatAmount(charge.amount),
                    "usageAmount" to charge.usageAmount,
                    "usageUnit" to charge.usageUnit,
                    "rate" to charge.rate?.let { result.formatAmount(it) },
                    "category" to charge.category.name
                )
            },
            "summary" to mapOf(
                "totalCharges" to result.formatAmount(result.totalCharges),
                "totalCredits" to result.formatAmount(result.totalCredits),
                "accountBalanceBefore" to result.formatAmount(result.accountBalanceBefore.balance),
                "accountBalanceAfter" to result.formatAmount(result.accountBalanceAfter.balance),
                "amountDue" to result.formatAmount(result.amountDue),
                "dueDate" to result.dueDate.toString()
            ),
            "meta" to mapOf(
                "computedAt" to result.computedAt.toString(),
                "engine" to "BillingEngine v1.0 (billing-domain)",
                "legacy" to false
            )
        )
    }
    
    /**
     * Create a sample electric bill input for demonstration.
     *
     * Simulates a residential customer with:
     * - 750 kWh usage for the month
     * - Tiered rate structure (increasing rates at higher usage)
     * - $10 readiness-to-serve charge
     * - Zero prior balance
     */
    private fun createSampleElectricBill(): BillInput {
        val utilityId = UtilityId("util-demo-electric-001")
        val customerId = CustomerId("cust-demo-residential-001")
        val billPeriod = BillingPeriod(
            id = "2025-01-MONTHLY",
            utilityId = utilityId,
            startDate = LocalDate.of(2025, 1, 1),
            endDate = LocalDate.of(2025, 1, 31),
            billDate = LocalDate.of(2025, 2, 1),
            dueDate = LocalDate.of(2025, 2, 21),
            frequency = BillingFrequency.MONTHLY
        )
        
        // Meter readings: 1000 kWh at start, 1750 kWh at end = 750 kWh consumption
        val meterReadPair = MeterReadPair(
            meterId = "meter-electric-001",
            serviceType = ServiceType.ELECTRIC,
            usageType = UsageUnit.KWH,
            startRead = MeterRead(
                meterId = "meter-electric-001",
                serviceType = ServiceType.ELECTRIC,
                readingValue = 1000.0,
                readDate = billPeriod.startDate,
                usageUnit = UsageUnit.KWH,
                readingType = ReadingType.ACTUAL
            ),
            endRead = MeterRead(
                meterId = "meter-electric-001",
                serviceType = ServiceType.ELECTRIC,
                readingValue = 1750.0,
                readDate = billPeriod.endDate,
                usageUnit = UsageUnit.KWH,
                readingType = ReadingType.ACTUAL
            )
        )
        
        // Tiered rate structure (typical residential electric)
        // Tier 1: 0-500 kWh @ $0.10/kWh
        // Tier 2: 501-1000 kWh @ $0.14/kWh
        // Tier 3: 1000+ kWh @ $0.18/kWh
        val rateTariff = RateTariff.TieredRate(
            readinessToServeCharge = Money(10_00), // $10.00
            tiers = listOf(
                RateTier(maxUsage = 500.0, ratePerUnit = Money(10)), // $0.10/kWh
                RateTier(maxUsage = 1000.0, ratePerUnit = Money(14)), // $0.14/kWh
                RateTier(maxUsage = null, ratePerUnit = Money(18))  // $0.18/kWh (top tier)
            ),
            unit = "kWh"
        )
        
        return BillInput(
            billId = BillId("bill-demo-001"),
            billRunId = BillingCycleId("cycle-2025-01"),
            utilityId = utilityId,
            customerId = customerId,
            billPeriod = billPeriod,
            meterReads = listOf(meterReadPair),
            rateTariff = rateTariff,
            accountBalance = AccountBalance.zero()
        )
    }
    
    /**
     * Generate a multi-service bill (electric + water).
     *
     * Demonstrates multi-service billing capability.
     *
     * Example: GET /demo-multi-service-bill
     */
    @GetMapping("/demo-multi-service-bill")
    fun demoMultiServiceBill(): Map<String, Any> {
        val utilityId = UtilityId("util-demo-combined-001")
        val customerId = CustomerId("cust-demo-multi-001")
        val billPeriod = BillingPeriod(
            id = "2025-01-MONTHLY",
            utilityId = utilityId,
            startDate = LocalDate.of(2025, 1, 1),
            endDate = LocalDate.of(2025, 1, 31),
            billDate = LocalDate.of(2025, 2, 1),
            dueDate = LocalDate.of(2025, 2, 21),
            frequency = BillingFrequency.MONTHLY
        )
        
        // Electric service reads
        val electricReads = ServiceMeterReads(
            serviceType = ServiceType.ELECTRIC,
            reads = listOf(
                MeterReadPair(
                    meterId = "meter-electric-001",
                    serviceType = ServiceType.ELECTRIC,
                    usageType = UsageUnit.KWH,
                    startRead = MeterRead(
                        meterId = "meter-electric-001",
                        serviceType = ServiceType.ELECTRIC,
                        readingValue = 1000.0,
                        readDate = billPeriod.startDate,
                        usageUnit = UsageUnit.KWH
                    ),
                    endRead = MeterRead(
                        meterId = "meter-electric-001",
                        serviceType = ServiceType.ELECTRIC,
                        readingValue = 1600.0,
                        readDate = billPeriod.endDate,
                        usageUnit = UsageUnit.KWH
                    )
                )
            )
        )
        
        // Water service reads
        val waterReads = ServiceMeterReads(
            serviceType = ServiceType.WATER,
            reads = listOf(
                MeterReadPair(
                    meterId = "meter-water-001",
                    serviceType = ServiceType.WATER,
                    usageType = UsageUnit.CCF,
                    startRead = MeterRead(
                        meterId = "meter-water-001",
                        serviceType = ServiceType.WATER,
                        readingValue = 500.0,
                        readDate = billPeriod.startDate,
                        usageUnit = UsageUnit.CCF
                    ),
                    endRead = MeterRead(
                        meterId = "meter-water-001",
                        serviceType = ServiceType.WATER,
                        readingValue = 515.0,
                        readDate = billPeriod.endDate,
                        usageUnit = UsageUnit.CCF
                    )
                )
            )
        )
        
        // Electric tariff (flat rate)
        val electricTariff = RateTariff.FlatRate(
            readinessToServeCharge = Money(10_00), // $10
            ratePerUnit = Money(12), // $0.12/kWh
            unit = "kWh"
        )
        
        // Water tariff (flat rate)
        val waterTariff = RateTariff.FlatRate(
            readinessToServeCharge = Money(8_00), // $8
            ratePerUnit = Money(450), // $4.50/CCF
            unit = "CCF"
        )
        
        val input = MultiServiceBillInput(
            billId = BillId("bill-multi-demo-001"),
            billRunId = BillingCycleId("cycle-2025-01"),
            utilityId = utilityId,
            customerId = customerId,
            billPeriod = billPeriod,
            serviceReads = listOf(electricReads, waterReads),
            serviceTariffs = mapOf(
                ServiceType.ELECTRIC to electricTariff,
                ServiceType.WATER to waterTariff
            ),
            accountBalance = AccountBalance.zero()
        )
        
        val result = BillingEngine.calculateMultiServiceBill(
            input = input,
            computedAt = Instant.now()
        )
        
        return mapOf(
            "message" to "Multi-service bill calculated using BillingEngine",
            "utilityId" to result.utilityId.value,
            "customerId" to result.customerId.value,
            "billId" to result.billId.value,
            "services" to listOf("ELECTRIC", "WATER"),
            "charges" to result.charges.map { charge ->
                mapOf(
                    "code" to charge.code,
                    "description" to charge.description,
                    "amount" to result.formatAmount(charge.amount),
                    "serviceType" to (charge.serviceType?.name ?: "ACCOUNT"),
                    "category" to charge.category.name
                )
            },
            "summary" to mapOf(
                "totalCharges" to result.formatAmount(result.totalCharges),
                "amountDue" to result.formatAmount(result.amountDue),
                "dueDate" to result.dueDate.toString()
            )
        )
    }
}
