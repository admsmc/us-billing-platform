package com.example.usbilling.billing.engine

import com.example.usbilling.billing.model.*
import com.example.usbilling.shared.*
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for service-specific proration in multi-service billing.
 */
class ServiceSpecificProrationTest {

    @Test
    fun `bill with different proration dates per service works correctly`() {
        val period = BillingPeriod(
            id = "2025-01",
            utilityId = UtilityId("UTIL-001"),
            startDate = LocalDate.of(2025, 1, 1),
            endDate = LocalDate.of(2025, 1, 31),
            billDate = LocalDate.of(2025, 2, 1),
            dueDate = LocalDate.of(2025, 2, 15),
            frequency = BillingFrequency.MONTHLY,
        )

        val input = MultiServiceBillInput(
            billId = BillId("BILL-001"),
            billRunId = BillingCycleId("RUN-001"),
            utilityId = UtilityId("UTIL-001"),
            customerId = CustomerId("CUST-001"),
            billPeriod = period,
            serviceReads = listOf(
                // Electric - active full month
                ServiceMeterReads(
                    serviceType = ServiceType.ELECTRIC,
                    reads = listOf(
                        MeterReadPair(
                            meterId = "MTR-ELEC-001",
                            serviceType = ServiceType.ELECTRIC,
                            usageType = UsageUnit.KWH,
                            startRead = createRead("MTR-ELEC-001", 1000.0),
                            endRead = createRead("MTR-ELEC-001", 1500.0),
                        ),
                    ),
                    // No service dates - full month
                ),
                // Refuse - started mid-month
                ServiceMeterReads(
                    serviceType = ServiceType.REFUSE,
                    reads = emptyList(), // No meter for refuse
                    serviceStartDate = LocalDate.of(2025, 1, 16), // Started Jan 16
                    serviceEndDate = null,
                ),
                // Water - ending mid-month
                ServiceMeterReads(
                    serviceType = ServiceType.WATER,
                    reads = listOf(
                        MeterReadPair(
                            meterId = "MTR-WATER-001",
                            serviceType = ServiceType.WATER,
                            usageType = UsageUnit.CCF,
                            startRead = createRead("MTR-WATER-001", 100.0),
                            endRead = createRead("MTR-WATER-001", 115.0),
                        ),
                    ),
                    serviceStartDate = null,
                    serviceEndDate = LocalDate.of(2025, 1, 15), // Ending Jan 15
                ),
            ),
            serviceTariffs = mapOf(
                ServiceType.ELECTRIC to RateTariff.FlatRate(
                    readinessToServeCharge = Money(3100), // $31.00
                    ratePerUnit = Money(10),
                    unit = "kWh",
                ),
                ServiceType.REFUSE to RateTariff.FlatRate(
                    readinessToServeCharge = Money(2000), // $20.00
                    ratePerUnit = Money(0),
                    unit = "none",
                ),
                ServiceType.WATER to RateTariff.FlatRate(
                    readinessToServeCharge = Money(1500), // $15.00
                    ratePerUnit = Money(500),
                    unit = "CCF",
                ),
            ),
            accountBalance = AccountBalance.zero(),
            prorationConfig = ProrationConfig.default(),
        )

        val result = BillingEngine.calculateMultiServiceBill(input)

        // Electric - full month charge
        val electricReadiness = result.charges.find {
            it.code == "ELECTRIC_READINESS_TO_SERVE"
        }
        assertTrue(electricReadiness != null)
        assertEquals(Money(3100), electricReadiness.amount)
        assertTrue(!electricReadiness.description.contains("prorated"))

        // Refuse - prorated for 16 days (Jan 16-31) out of 31
        // $20.00 * (16/31) = $10.32 = 1032 cents
        val refuseReadiness = result.charges.find {
            it.code == "REFUSE_READINESS_TO_SERVE"
        }
        assertTrue(refuseReadiness != null)
        assertEquals(Money(1032), refuseReadiness.amount)
        assertTrue(refuseReadiness.description.contains("prorated"))

        // Water - prorated for 15 days (Jan 1-15) out of 31
        // $15.00 * (15/31) = $7.25 (rounded) = 725 cents
        val waterReadiness = result.charges.find {
            it.code == "WATER_READINESS_TO_SERVE"
        }
        assertTrue(waterReadiness != null)
        assertEquals(Money(725), waterReadiness.amount)
        assertTrue(waterReadiness.description.contains("prorated"))
    }

    @Test
    fun `service-specific proration overrides global proration`() {
        val period = BillingPeriod(
            id = "2025-01",
            utilityId = UtilityId("UTIL-001"),
            startDate = LocalDate.of(2025, 1, 1),
            endDate = LocalDate.of(2025, 1, 31),
            billDate = LocalDate.of(2025, 2, 1),
            dueDate = LocalDate.of(2025, 2, 15),
            frequency = BillingFrequency.MONTHLY,
        )

        val input = MultiServiceBillInput(
            billId = BillId("BILL-001"),
            billRunId = BillingCycleId("RUN-001"),
            utilityId = UtilityId("UTIL-001"),
            customerId = CustomerId("CUST-001"),
            billPeriod = period,
            serviceReads = listOf(
                // Electric with service-specific dates (overrides global)
                ServiceMeterReads(
                    serviceType = ServiceType.ELECTRIC,
                    reads = emptyList(),
                    serviceStartDate = LocalDate.of(2025, 1, 11), // 21 days
                    serviceEndDate = null,
                ),
                // Water uses global dates
                ServiceMeterReads(
                    serviceType = ServiceType.WATER,
                    reads = emptyList(),
                    // No service-specific dates - will use global
                ),
            ),
            serviceTariffs = mapOf(
                ServiceType.ELECTRIC to RateTariff.FlatRate(
                    readinessToServeCharge = Money(3100),
                    ratePerUnit = Money(10),
                    unit = "kWh",
                ),
                ServiceType.WATER to RateTariff.FlatRate(
                    readinessToServeCharge = Money(1500),
                    ratePerUnit = Money(500),
                    unit = "CCF",
                ),
            ),
            accountBalance = AccountBalance.zero(),
            serviceStartDate = LocalDate.of(2025, 1, 16), // Global: 16 days
            prorationConfig = ProrationConfig.default(),
        )

        val result = BillingEngine.calculateMultiServiceBill(input)

        // Electric uses service-specific: 21 days (Jan 11-31) out of 31
        // $31.00 * (21/31) = $21.00 = 2100 cents
        val electricReadiness = result.charges.find {
            it.code == "ELECTRIC_READINESS_TO_SERVE"
        }
        assertTrue(electricReadiness != null)
        assertEquals(Money(2100), electricReadiness.amount)

        // Water uses global: 16 days (Jan 16-31) out of 31
        // $15.00 * (16/31) = $7.74 = 774 cents
        val waterReadiness = result.charges.find {
            it.code == "WATER_READINESS_TO_SERVE"
        }
        assertTrue(waterReadiness != null)
        assertEquals(Money(774), waterReadiness.amount)
    }

    @Test
    fun `adding service mid-month with proration`() {
        // Scenario: Customer adds trash collection on Jan 15
        // Should only pay for Jan 15-31 (17 days)

        val period = BillingPeriod(
            id = "2025-01",
            utilityId = UtilityId("UTIL-001"),
            startDate = LocalDate.of(2025, 1, 1),
            endDate = LocalDate.of(2025, 1, 31),
            billDate = LocalDate.of(2025, 2, 1),
            dueDate = LocalDate.of(2025, 2, 15),
            frequency = BillingFrequency.MONTHLY,
        )

        val input = MultiServiceBillInput(
            billId = BillId("BILL-001"),
            billRunId = BillingCycleId("RUN-001"),
            utilityId = UtilityId("UTIL-001"),
            customerId = CustomerId("CUST-001"),
            billPeriod = period,
            serviceReads = listOf(
                ServiceMeterReads(
                    serviceType = ServiceType.REFUSE,
                    reads = emptyList(),
                    serviceStartDate = LocalDate.of(2025, 1, 15),
                ),
            ),
            serviceTariffs = mapOf(
                ServiceType.REFUSE to RateTariff.FlatRate(
                    readinessToServeCharge = Money(2500), // $25.00/month
                    ratePerUnit = Money(0),
                    unit = "none",
                ),
            ),
            accountBalance = AccountBalance.zero(),
            prorationConfig = ProrationConfig.default(),
        )

        val result = BillingEngine.calculateMultiServiceBill(input)

        // Refuse for 17 days (Jan 15-31) out of 31
        // $25.00 * (17/31) = $13.70 (rounded) = 1370 cents
        val refuseCharge = result.charges.find {
            it.code == "REFUSE_READINESS_TO_SERVE"
        }
        assertTrue(refuseCharge != null)
        assertEquals(Money(1370), refuseCharge.amount)
        assertTrue(refuseCharge.description.contains("prorated"))
    }

    private fun createRead(meterId: String, value: Double): MeterRead = MeterRead(
        meterId = meterId,
        serviceType = ServiceType.ELECTRIC,
        readingValue = value,
        readDate = LocalDate.now(),
        usageUnit = UsageUnit.KWH,
    )
}
