package com.example.usbilling.billing.engine

import com.example.usbilling.billing.model.*
import com.example.usbilling.shared.BillId
import com.example.usbilling.shared.BillingCycleId
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.Money
import com.example.usbilling.shared.UtilityId
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BillingEngineTest {
    
    @Test
    fun `calculateBill with flat rate generates correct charges`() {
        // Given: 500 kWh usage at $0.12/kWh with $10 readiness-to-serve
        val input = BillInput(
            billId = BillId("test-bill-001"),
            billRunId = BillingCycleId("test-cycle-001"),
            utilityId = UtilityId("test-util-001"),
            customerId = CustomerId("test-cust-001"),
            billPeriod = BillingPeriod(
                id = "2025-01",
                utilityId = UtilityId("test-util-001"),
                startDate = LocalDate.of(2025, 1, 1),
                endDate = LocalDate.of(2025, 1, 31),
                billDate = LocalDate.of(2025, 2, 1),
                dueDate = LocalDate.of(2025, 2, 21),
                frequency = BillingFrequency.MONTHLY
            ),
            meterReads = listOf(
                MeterReadPair(
                    meterId = "meter-001",
                    serviceType = ServiceType.ELECTRIC,
                    usageType = UsageUnit.KWH,
                    startRead = MeterRead(
                        meterId = "meter-001",
                        serviceType = ServiceType.ELECTRIC,
                        readingValue = 1000.0,
                        readDate = LocalDate.of(2025, 1, 1),
                        usageUnit = UsageUnit.KWH
                    ),
                    endRead = MeterRead(
                        meterId = "meter-001",
                        serviceType = ServiceType.ELECTRIC,
                        readingValue = 1500.0,
                        readDate = LocalDate.of(2025, 1, 31),
                        usageUnit = UsageUnit.KWH
                    )
                )
            ),
            rateTariff = RateTariff.FlatRate(
                readinessToServeCharge = Money(10_00), // $10.00
                ratePerUnit = Money(12), // $0.12/kWh
                unit = "kWh"
            ),
            accountBalance = AccountBalance.zero()
        )
        
        // When
        val result = BillingEngine.calculateBill(input, Instant.now())
        
        // Then
        // Readiness-to-serve: $10.00
        // Usage charge: 500 kWh × $0.12 = $60.00
        // Total: $70.00
        assertEquals(2, result.charges.size)
        
        val readinessCharge = result.charges.find { it.category == ChargeCategory.READINESS_TO_SERVE }
        assertEquals(Money(10_00), readinessCharge?.amount)
        
        val usageCharge = result.charges.find { it.category == ChargeCategory.USAGE_CHARGE }
        assertEquals(Money(60_00), usageCharge?.amount)
        assertEquals(500.0, usageCharge?.usageAmount)
        
        assertEquals(Money(70_00), result.totalCharges)
        assertEquals(Money(70_00), result.amountDue)
    }
    
    @Test
    fun `calculateBill with tiered rate applies tiers correctly`() {
        // Given: 750 kWh usage with tiered rates
        // Tier 1: 0-500 @ $0.10/kWh = $50.00
        // Tier 2: 501-1000 @ $0.14/kWh, but only 250 kWh = $35.00
        // Total usage charges: $85.00
        // Readiness-to-serve: $10.00
        // Grand total: $95.00
        val input = BillInput(
            billId = BillId("test-bill-002"),
            billRunId = BillingCycleId("test-cycle-001"),
            utilityId = UtilityId("test-util-001"),
            customerId = CustomerId("test-cust-002"),
            billPeriod = BillingPeriod(
                id = "2025-01",
                utilityId = UtilityId("test-util-001"),
                startDate = LocalDate.of(2025, 1, 1),
                endDate = LocalDate.of(2025, 1, 31),
                billDate = LocalDate.of(2025, 2, 1),
                dueDate = LocalDate.of(2025, 2, 21),
                frequency = BillingFrequency.MONTHLY
            ),
            meterReads = listOf(
                MeterReadPair(
                    meterId = "meter-002",
                    serviceType = ServiceType.ELECTRIC,
                    usageType = UsageUnit.KWH,
                    startRead = MeterRead(
                        meterId = "meter-002",
                        serviceType = ServiceType.ELECTRIC,
                        readingValue = 1000.0,
                        readDate = LocalDate.of(2025, 1, 1),
                        usageUnit = UsageUnit.KWH
                    ),
                    endRead = MeterRead(
                        meterId = "meter-002",
                        serviceType = ServiceType.ELECTRIC,
                        readingValue = 1750.0,
                        readDate = LocalDate.of(2025, 1, 31),
                        usageUnit = UsageUnit.KWH
                    )
                )
            ),
            rateTariff = RateTariff.TieredRate(
                readinessToServeCharge = Money(10_00),
                tiers = listOf(
                    RateTier(maxUsage = 500.0, ratePerUnit = Money(10)), // $0.10/kWh
                    RateTier(maxUsage = 1000.0, ratePerUnit = Money(14)), // $0.14/kWh
                    RateTier(maxUsage = null, ratePerUnit = Money(18))  // $0.18/kWh
                ),
                unit = "kWh"
            ),
            accountBalance = AccountBalance.zero()
        )
        
        // When
        val result = BillingEngine.calculateBill(input, Instant.now())
        
        // Then
        assertEquals(3, result.charges.size) // Readiness-to-serve + Tier 1 + Tier 2
        
        val tier1Charge = result.charges.find { it.code == "TIER1" }
        assertEquals(Money(50_00), tier1Charge?.amount) // 500 kWh × $0.10
        assertEquals(500.0, tier1Charge?.usageAmount)
        
        val tier2Charge = result.charges.find { it.code == "TIER2" }
        assertEquals(Money(35_00), tier2Charge?.amount) // 250 kWh × $0.14
        assertEquals(250.0, tier2Charge?.usageAmount)
        
        assertEquals(Money(95_00), result.totalCharges)
        assertEquals(Money(95_00), result.amountDue)
    }
    
    @Test
    fun `calculateBill with prior balance adds to amount due`() {
        // Given: Customer has $25 prior balance
        val input = BillInput(
            billId = BillId("test-bill-003"),
            billRunId = BillingCycleId("test-cycle-001"),
            utilityId = UtilityId("test-util-001"),
            customerId = CustomerId("test-cust-003"),
            billPeriod = BillingPeriod(
                id = "2025-01",
                utilityId = UtilityId("test-util-001"),
                startDate = LocalDate.of(2025, 1, 1),
                endDate = LocalDate.of(2025, 1, 31),
                billDate = LocalDate.of(2025, 2, 1),
                dueDate = LocalDate.of(2025, 2, 21),
                frequency = BillingFrequency.MONTHLY
            ),
            meterReads = listOf(
                MeterReadPair(
                    meterId = "meter-003",
                    serviceType = ServiceType.ELECTRIC,
                    usageType = UsageUnit.KWH,
                    startRead = MeterRead(
                        meterId = "meter-003",
                        serviceType = ServiceType.ELECTRIC,
                        readingValue = 1000.0,
                        readDate = LocalDate.of(2025, 1, 1),
                        usageUnit = UsageUnit.KWH
                    ),
                    endRead = MeterRead(
                        meterId = "meter-003",
                        serviceType = ServiceType.ELECTRIC,
                        readingValue = 1500.0,
                        readDate = LocalDate.of(2025, 1, 31),
                        usageUnit = UsageUnit.KWH
                    )
                )
            ),
            rateTariff = RateTariff.FlatRate(
                readinessToServeCharge = Money(10_00),
                ratePerUnit = Money(12), // $0.12/kWh
                unit = "kWh"
            ),
            accountBalance = AccountBalance(balance = Money(25_00)) // $25 prior balance
        )
        
        // When
        val result = BillingEngine.calculateBill(input, Instant.now())
        
        // Then
        // Current charges: $70.00
        // Prior balance: $25.00
        // Amount due: $95.00
        assertEquals(Money(70_00), result.totalCharges)
        assertEquals(Money(25_00), result.accountBalanceBefore.balance)
        assertEquals(Money(95_00), result.amountDue)
        assertEquals(Money(95_00), result.accountBalanceAfter.balance)
    }
    
    @Test
    fun `calculateMultiServiceBill combines electric and water charges`() {
        // Given: Multi-service account with electric and water
        val utilityId = UtilityId("test-util-multi-001")
        val billPeriod = BillingPeriod(
            id = "2025-01",
            utilityId = utilityId,
            startDate = LocalDate.of(2025, 1, 1),
            endDate = LocalDate.of(2025, 1, 31),
            billDate = LocalDate.of(2025, 2, 1),
            dueDate = LocalDate.of(2025, 2, 21),
            frequency = BillingFrequency.MONTHLY
        )
        
        val input = MultiServiceBillInput(
            billId = BillId("test-multi-bill-001"),
            billRunId = BillingCycleId("test-cycle-001"),
            utilityId = utilityId,
            customerId = CustomerId("test-multi-cust-001"),
            billPeriod = billPeriod,
            serviceReads = listOf(
                ServiceMeterReads(
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
                                readingValue = 1500.0,
                                readDate = billPeriod.endDate,
                                usageUnit = UsageUnit.KWH
                            )
                        )
                    )
                ),
                ServiceMeterReads(
                    serviceType = ServiceType.WATER,
                    reads = listOf(
                        MeterReadPair(
                            meterId = "meter-water-001",
                            serviceType = ServiceType.WATER,
                            usageType = UsageUnit.CCF,
                            startRead = MeterRead(
                                meterId = "meter-water-001",
                                serviceType = ServiceType.WATER,
                                readingValue = 100.0,
                                readDate = billPeriod.startDate,
                                usageUnit = UsageUnit.CCF
                            ),
                            endRead = MeterRead(
                                meterId = "meter-water-001",
                                serviceType = ServiceType.WATER,
                                readingValue = 110.0,
                                readDate = billPeriod.endDate,
                                usageUnit = UsageUnit.CCF
                            )
                        )
                    )
                )
            ),
            serviceTariffs = mapOf(
                ServiceType.ELECTRIC to RateTariff.FlatRate(
                    readinessToServeCharge = Money(10_00),
                    ratePerUnit = Money(12), // $0.12/kWh
                    unit = "kWh"
                ),
                ServiceType.WATER to RateTariff.FlatRate(
                    readinessToServeCharge = Money(8_00),
                    ratePerUnit = Money(450), // $4.50/CCF
                    unit = "CCF"
                )
            ),
            accountBalance = AccountBalance.zero()
        )
        
        // When
        val result = BillingEngine.calculateMultiServiceBill(input, Instant.now())
        
        // Then
        // Electric: $10 readiness + 500 kWh × $0.12 = $70.00
        // Water: $8 readiness + 10 CCF × $4.50 = $53.00
        // Total: $123.00
        assertTrue(result.charges.size >= 4) // 2 readiness + 2 usage
        
        val electricCharges = result.charges.filter { it.serviceType == ServiceType.ELECTRIC }
        assertEquals(2, electricCharges.size)
        
        val waterCharges = result.charges.filter { it.serviceType == ServiceType.WATER }
        assertEquals(2, waterCharges.size)
        
        assertEquals(Money(123_00), result.totalCharges)
        assertEquals(Money(123_00), result.amountDue)
    }
}
