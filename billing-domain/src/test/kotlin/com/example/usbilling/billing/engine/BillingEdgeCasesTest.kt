package com.example.usbilling.billing.engine

import com.example.usbilling.billing.model.*
import com.example.usbilling.shared.*
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BillingEdgeCasesTest {

    @Test
    fun `meter rollover calculation handles correctly`() {
        // Meter reading rolls over from 99999 to 00050
        val startRead = MeterRead(
            meterId = "MTR-001",
            serviceType = ServiceType.ELECTRIC,
            readingValue = 99950.0,
            readDate = LocalDate.of(2025, 1, 1),
            usageUnit = UsageUnit.KWH,
        )

        val endRead = MeterRead(
            meterId = "MTR-001",
            serviceType = ServiceType.ELECTRIC,
            readingValue = 50.0, // Rolled over
            readDate = LocalDate.of(2025, 1, 31),
            usageUnit = UsageUnit.KWH,
        )

        val meterPair = MeterReadPair(
            meterId = "MTR-001",
            serviceType = ServiceType.ELECTRIC,
            usageType = UsageUnit.KWH,
            startRead = startRead,
            endRead = endRead,
        )

        val consumption = meterPair.calculateConsumption()

        // Should calculate: 100,000 - 99,950 + 50 = 100
        assertEquals(100.0, consumption, 0.01)
    }

    @Test
    fun `negative consumption is handled as meter rollover`() {
        val meterPair = MeterReadPair(
            meterId = "MTR-001",
            serviceType = ServiceType.ELECTRIC,
            usageType = UsageUnit.KWH,
            startRead = MeterRead(
                meterId = "MTR-001",
                serviceType = ServiceType.ELECTRIC,
                readingValue = 1000.0,
                readDate = LocalDate.now(),
                usageUnit = UsageUnit.KWH,
            ),
            endRead = MeterRead(
                meterId = "MTR-001",
                serviceType = ServiceType.ELECTRIC,
                readingValue = 100.0, // Lower than start
                readDate = LocalDate.now(),
                usageUnit = UsageUnit.KWH,
            ),
        )

        val consumption = meterPair.calculateConsumption()

        // Should be positive (rollover detected)
        assertTrue(consumption > 0.0)
        assertEquals(99100.0, consumption, 0.01) // 100,000 - 900
    }

    @Test
    fun `multiple meters per customer are calculated correctly`() {
        val tariff = RateTariff.FlatRate(
            readinessToServeCharge = Money(2000),
            ratePerUnit = Money(15),
            unit = "kWh",
        )

        // Customer has two electric meters
        val meterReads = listOf(
            MeterReadPair(
                meterId = "MTR-001",
                serviceType = ServiceType.ELECTRIC,
                usageType = UsageUnit.KWH,
                startRead = createRead("MTR-001", 1000.0),
                endRead = createRead("MTR-001", 1500.0), // 500 kWh
            ),
            MeterReadPair(
                meterId = "MTR-002",
                serviceType = ServiceType.ELECTRIC,
                usageType = UsageUnit.KWH,
                startRead = createRead("MTR-002", 2000.0),
                endRead = createRead("MTR-002", 2300.0), // 300 kWh
            ),
        )

        val input = BillInput(
            billId = BillId("BILL-001"),
            billRunId = BillingCycleId("RUN-001"),
            utilityId = UtilityId("UTIL-001"),
            customerId = CustomerId("CUST-001"),
            billPeriod = createTestPeriod(),
            meterReads = meterReads,
            rateTariff = tariff,
            accountBalance = AccountBalance.zero(),
        )

        val result = BillingEngine.calculateBill(input)

        // Should have 1 customer charge + 2 usage charges (one per meter)
        assertEquals(3, result.charges.size)

        val usageCharges = result.charges.filter { it.category == ChargeCategory.USAGE_CHARGE }
        assertEquals(2, usageCharges.size)

        // Total usage: 800 kWh * $0.15 = $120.00
        val totalUsageCharge = usageCharges.sumOf { it.amount.amount }
        assertEquals(12000L, totalUsageCharge)
    }

    @Test
    fun `partial billing period proration works correctly`() {
        val period = BillingPeriod(
            id = "2025-01",
            utilityId = UtilityId("UTIL-001"),
            startDate = LocalDate.of(2025, 1, 1),
            endDate = LocalDate.of(2025, 1, 31),
            billDate = LocalDate.of(2025, 2, 1),
            dueDate = LocalDate.of(2025, 2, 15),
            frequency = BillingFrequency.MONTHLY,
        )

        // Service only from Jan 15 to Jan 31
        val prorationFactor = period.prorationFactor(
            LocalDate.of(2025, 1, 15),
            LocalDate.of(2025, 1, 31),
        )

        // 17 days out of 31 days
        assertEquals(17.0 / 31.0, prorationFactor, 0.01)
    }

    @Test
    fun `zero consumption produces readiness to serve charge only`() {
        val tariff = RateTariff.FlatRate(
            readinessToServeCharge = Money(1500),
            ratePerUnit = Money(10),
            unit = "kWh",
        )

        val input = BillInput(
            billId = BillId("BILL-001"),
            billRunId = BillingCycleId("RUN-001"),
            utilityId = UtilityId("UTIL-001"),
            customerId = CustomerId("CUST-001"),
            billPeriod = createTestPeriod(),
            meterReads = listOf(
                MeterReadPair(
                    meterId = "MTR-001",
                    serviceType = ServiceType.ELECTRIC,
                    usageType = UsageUnit.KWH,
                    startRead = createRead("MTR-001", 1000.0),
                    endRead = createRead("MTR-001", 1000.0), // No usage
                ),
            ),
            rateTariff = tariff,
            accountBalance = AccountBalance.zero(),
        )

        val result = BillingEngine.calculateBill(input)

        // Should have readiness to serve charge + zero usage charge
        val readinessCharge = result.charges.find { it.code == "READINESS_TO_SERVE" }
        assertTrue(readinessCharge != null)
        assertEquals(Money(1500), readinessCharge.amount)

        val usageCharge = result.charges.find { it.code == "USAGE" }
        assertTrue(usageCharge != null)
        assertEquals(Money(0), usageCharge.amount)
    }

    @Test
    fun `demand rate with demand charges calculates correctly`() {
        val tariff = RateTariff.DemandRate(
            readinessToServeCharge = Money(5000), // $50
            energyRatePerUnit = Money(8), // $0.08/kWh
            demandRatePerKw = Money(1200), // $12/kW
            unit = "kWh",
        )

        val demandReadings = listOf(
            DemandReading(
                meterId = "MTR-001",
                peakDemandKw = 150.0, // 150 kW peak
                timestamp = java.time.Instant.parse("2025-01-15T14:30:00Z"),
            ),
        )

        val input = BillInput(
            billId = BillId("BILL-001"),
            billRunId = BillingCycleId("RUN-001"),
            utilityId = UtilityId("UTIL-001"),
            customerId = CustomerId("CUST-001"),
            billPeriod = createTestPeriod(),
            meterReads = listOf(
                MeterReadPair(
                    meterId = "MTR-001",
                    serviceType = ServiceType.ELECTRIC,
                    usageType = UsageUnit.KWH,
                    startRead = createRead("MTR-001", 10000.0),
                    endRead = createRead("MTR-001", 15000.0), // 5,000 kWh
                ),
            ),
            rateTariff = tariff,
            accountBalance = AccountBalance.zero(),
            demandReadings = demandReadings,
        )

        val result = BillingEngine.calculateBill(input)

        // Should have: readiness-to-serve charge + energy charge + demand charge
        val energyCharge = result.charges.find { it.code == "ENERGY" }
        assertTrue(energyCharge != null)
        assertEquals(Money(40000), energyCharge.amount) // 5,000 * $0.08

        val demandCharge = result.charges.find { it.code == "DEMAND" }
        assertTrue(demandCharge != null)
        assertEquals(Money(180000), demandCharge.amount) // 150 * $12
    }

    @Test
    fun `regulatory surcharge fixed amount is added`() {
        val surcharges = listOf(
            RegulatoryCharge(
                code = "PCA",
                description = "Power Cost Adjustment",
                calculationType = RegulatoryChargeType.FIXED,
                rate = Money(250), // $2.50 fixed
            ),
        )

        val tariff = RateTariff.FlatRate(
            readinessToServeCharge = Money(1500),
            ratePerUnit = Money(10),
            unit = "kWh",
            regulatorySurcharges = surcharges,
        )

        val input = BillInput(
            billId = BillId("BILL-001"),
            billRunId = BillingCycleId("RUN-001"),
            utilityId = UtilityId("UTIL-001"),
            customerId = CustomerId("CUST-001"),
            billPeriod = createTestPeriod(),
            meterReads = listOf(
                MeterReadPair(
                    meterId = "MTR-001",
                    serviceType = ServiceType.ELECTRIC,
                    usageType = UsageUnit.KWH,
                    startRead = createRead("MTR-001", 1000.0),
                    endRead = createRead("MTR-001", 1500.0),
                ),
            ),
            rateTariff = tariff,
            accountBalance = AccountBalance.zero(),
        )

        val result = BillingEngine.calculateBill(input)

        val pcaCharge = result.charges.find { it.code == "PCA" }
        assertTrue(pcaCharge != null)
        assertEquals(Money(250), pcaCharge.amount)
        assertEquals(ChargeCategory.REGULATORY_CHARGE, pcaCharge.category)
    }

    private fun createRead(meterId: String, value: Double): MeterRead = MeterRead(
        meterId = meterId,
        serviceType = ServiceType.ELECTRIC,
        readingValue = value,
        readDate = LocalDate.now(),
        usageUnit = UsageUnit.KWH,
    )

    private fun createTestPeriod(): BillingPeriod = BillingPeriod(
        id = "2025-01",
        utilityId = UtilityId("UTIL-001"),
        startDate = LocalDate.of(2025, 1, 1),
        endDate = LocalDate.of(2025, 1, 31),
        billDate = LocalDate.of(2025, 2, 1),
        dueDate = LocalDate.of(2025, 2, 15),
        frequency = BillingFrequency.MONTHLY,
    )

    @Test
    fun `bill with mid-period service start prorates readiness-to-serve charge`() {
        val period = BillingPeriod(
            id = "2025-01",
            utilityId = UtilityId("UTIL-001"),
            startDate = LocalDate.of(2025, 1, 1),
            endDate = LocalDate.of(2025, 1, 31),
            billDate = LocalDate.of(2025, 2, 1),
            dueDate = LocalDate.of(2025, 2, 15),
            frequency = BillingFrequency.MONTHLY,
        )

        val tariff = RateTariff.FlatRate(
            readinessToServeCharge = Money(3100), // $31.00 for full month
            ratePerUnit = Money(10),
            unit = "kWh",
        )

        val input = BillInput(
            billId = BillId("BILL-001"),
            billRunId = BillingCycleId("RUN-001"),
            utilityId = UtilityId("UTIL-001"),
            customerId = CustomerId("CUST-001"),
            billPeriod = period,
            meterReads = listOf(
                MeterReadPair(
                    meterId = "MTR-001",
                    serviceType = ServiceType.ELECTRIC,
                    usageType = UsageUnit.KWH,
                    startRead = createRead("MTR-001", 1000.0),
                    endRead = createRead("MTR-001", 1500.0),
                ),
            ),
            rateTariff = tariff,
            accountBalance = AccountBalance.zero(),
            serviceStartDate = LocalDate.of(2025, 1, 16), // Service starts Jan 16
            serviceEndDate = null, // Service continues through end of period
        )

        val result = BillingEngine.calculateBill(input)

        // Service is active for 16 days (Jan 16-31) out of 31 days
        // Expected proration: $31.00 * (16/31) = $16.00
        val readinessCharge = result.charges.find { it.code == "READINESS_TO_SERVE" }
        assertTrue(readinessCharge != null)
        assertTrue(readinessCharge.description.contains("prorated"))
        assertEquals(Money(1600), readinessCharge.amount)
    }

    @Test
    fun `bill with mid-period service end prorates readiness-to-serve charge`() {
        val period = BillingPeriod(
            id = "2025-01",
            utilityId = UtilityId("UTIL-001"),
            startDate = LocalDate.of(2025, 1, 1),
            endDate = LocalDate.of(2025, 1, 31),
            billDate = LocalDate.of(2025, 2, 1),
            dueDate = LocalDate.of(2025, 2, 15),
            frequency = BillingFrequency.MONTHLY,
        )

        val tariff = RateTariff.FlatRate(
            readinessToServeCharge = Money(3100), // $31.00 for full month
            ratePerUnit = Money(10),
            unit = "kWh",
        )

        val input = BillInput(
            billId = BillId("BILL-001"),
            billRunId = BillingCycleId("RUN-001"),
            utilityId = UtilityId("UTIL-001"),
            customerId = CustomerId("CUST-001"),
            billPeriod = period,
            meterReads = listOf(
                MeterReadPair(
                    meterId = "MTR-001",
                    serviceType = ServiceType.ELECTRIC,
                    usageType = UsageUnit.KWH,
                    startRead = createRead("MTR-001", 1000.0),
                    endRead = createRead("MTR-001", 1500.0),
                ),
            ),
            rateTariff = tariff,
            accountBalance = AccountBalance.zero(),
            serviceStartDate = null, // Service active from start
            serviceEndDate = LocalDate.of(2025, 1, 15), // Service ends Jan 15
        )

        val result = BillingEngine.calculateBill(input)

        // Service is active for 15 days (Jan 1-15) out of 31 days
        // Expected proration: $31.00 * (15/31) = $15.00
        val readinessCharge = result.charges.find { it.code == "READINESS_TO_SERVE" }
        assertTrue(readinessCharge != null)
        assertTrue(readinessCharge.description.contains("prorated"))
        assertEquals(Money(1500), readinessCharge.amount)
    }

    @Test
    fun `bill with proration config none does not prorate charges`() {
        val period = BillingPeriod(
            id = "2025-01",
            utilityId = UtilityId("UTIL-001"),
            startDate = LocalDate.of(2025, 1, 1),
            endDate = LocalDate.of(2025, 1, 31),
            billDate = LocalDate.of(2025, 2, 1),
            dueDate = LocalDate.of(2025, 2, 15),
            frequency = BillingFrequency.MONTHLY,
        )

        val tariff = RateTariff.FlatRate(
            readinessToServeCharge = Money(3100),
            ratePerUnit = Money(10),
            unit = "kWh",
        )

        val input = BillInput(
            billId = BillId("BILL-001"),
            billRunId = BillingCycleId("RUN-001"),
            utilityId = UtilityId("UTIL-001"),
            customerId = CustomerId("CUST-001"),
            billPeriod = period,
            meterReads = listOf(
                MeterReadPair(
                    meterId = "MTR-001",
                    serviceType = ServiceType.ELECTRIC,
                    usageType = UsageUnit.KWH,
                    startRead = createRead("MTR-001", 1000.0),
                    endRead = createRead("MTR-001", 1500.0),
                ),
            ),
            rateTariff = tariff,
            accountBalance = AccountBalance.zero(),
            serviceStartDate = LocalDate.of(2025, 1, 16),
            prorationConfig = ProrationConfig.none(), // Disable proration
        )

        val result = BillingEngine.calculateBill(input)

        // Should charge full amount despite partial period
        val readinessCharge = result.charges.find { it.code == "READINESS_TO_SERVE" }
        assertTrue(readinessCharge != null)
        assertEquals(Money(3100), readinessCharge.amount)
    }

    @Test
    fun `fixed regulatory surcharge is prorated when configured`() {
        val period = BillingPeriod(
            id = "2025-01",
            utilityId = UtilityId("UTIL-001"),
            startDate = LocalDate.of(2025, 1, 1),
            endDate = LocalDate.of(2025, 1, 31),
            billDate = LocalDate.of(2025, 2, 1),
            dueDate = LocalDate.of(2025, 2, 15),
            frequency = BillingFrequency.MONTHLY,
        )

        val surcharges = listOf(
            RegulatorySurcharge(
                code = "SAF",
                description = "System Access Fee",
                calculationType = RegulatorySurchargeCalculation.FIXED,
                fixedAmount = Money(700), // $7.00 fixed per month
            ),
        )

        val input = MultiServiceBillInput(
            billId = BillId("BILL-001"),
            billRunId = BillingCycleId("RUN-001"),
            utilityId = UtilityId("UTIL-001"),
            customerId = CustomerId("CUST-001"),
            billPeriod = period,
            serviceReads = listOf(
                ServiceMeterReads(
                    serviceType = ServiceType.ELECTRIC,
                    reads = listOf(
                        MeterReadPair(
                            meterId = "MTR-001",
                            serviceType = ServiceType.ELECTRIC,
                            usageType = UsageUnit.KWH,
                            startRead = createRead("MTR-001", 1000.0),
                            endRead = createRead("MTR-001", 1500.0),
                        ),
                    ),
                ),
            ),
            serviceTariffs = mapOf(
                ServiceType.ELECTRIC to RateTariff.FlatRate(
                    readinessToServeCharge = Money(1500),
                    ratePerUnit = Money(10),
                    unit = "kWh",
                ),
            ),
            accountBalance = AccountBalance.zero(),
            regulatorySurcharges = surcharges,
            serviceStartDate = LocalDate.of(2025, 1, 16), // 16 days out of 31
            prorationConfig = ProrationConfig.default(), // Prorates fixed regulatory charges
        )

        val result = BillingEngine.calculateMultiServiceBill(input)

        // Surcharge should be prorated: $7.00 * (16/31) = $3.61 = $361 cents
        val surchargeCharge = result.charges.find { it.code == "SAF" }
        assertTrue(surchargeCharge != null)
        assertEquals(Money(361), surchargeCharge.amount)
    }
}
