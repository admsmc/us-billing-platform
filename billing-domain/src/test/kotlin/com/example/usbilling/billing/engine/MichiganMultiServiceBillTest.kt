package com.example.usbilling.billing.engine

import com.example.usbilling.billing.model.*
import com.example.usbilling.billing.model.michigan.MichiganElectricSurcharges
import com.example.usbilling.billing.model.michigan.MichiganWaterSurcharges
import com.example.usbilling.shared.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Tests for Michigan multi-service billing scenarios.
 */
class MichiganMultiServiceBillTest {
    
    @Test
    fun `multi-service bill calculates all service charges correctly`() {
        val utilityId = UtilityId("mi-util")
        val customerId = CustomerId("cust-001")
        val billPeriod = BillingPeriod(
            id = "202501",
            utilityId = utilityId,
            startDate = LocalDate.of(2025, 1, 1),
            endDate = LocalDate.of(2025, 1, 31),
            billDate = LocalDate.of(2025, 2, 1),
            dueDate = LocalDate.of(2025, 2, 15),
            frequency = BillingFrequency.MONTHLY
        )
        
        // Electric: 500 kWh
        val electricReads = ServiceMeterReads(
            serviceType = ServiceType.ELECTRIC,
            reads = listOf(
                MeterReadPair(
                    meterId = "ELEC-001",
                    serviceType = ServiceType.ELECTRIC,
                    usageType = UsageUnit.KWH,
                    startRead = MeterRead("ELEC-001", ServiceType.ELECTRIC, 1000.0, LocalDate.of(2025, 1, 1), UsageUnit.KWH),
                    endRead = MeterRead("ELEC-001", ServiceType.ELECTRIC, 1500.0, LocalDate.of(2025, 1, 31), UsageUnit.KWH)
                )
            )
        )
        
        // Water: 10 CCF
        val waterReads = ServiceMeterReads(
            serviceType = ServiceType.WATER,
            reads = listOf(
                MeterReadPair(
                    meterId = "WATER-001",
                    serviceType = ServiceType.WATER,
                    usageType = UsageUnit.CCF,
                    startRead = MeterRead("WATER-001", ServiceType.WATER, 500.0, LocalDate.of(2025, 1, 1), UsageUnit.CCF),
                    endRead = MeterRead("WATER-001", ServiceType.WATER, 510.0, LocalDate.of(2025, 1, 31), UsageUnit.CCF)
                )
            )
        )
        
        val electricTariff = RateTariff.FlatRate(
            readinessToServeCharge = Money(1000),
            ratePerUnit = Money(12),
            unit = "kWh"
        )
        
        val waterTariff = RateTariff.FlatRate(
            readinessToServeCharge = Money(800),
            ratePerUnit = Money(300),
            unit = "CCF"
        )
        
        val input = MultiServiceBillInput(
            billId = BillId("BILL-001"),
            billRunId = BillRunId("RUN-001"),
            utilityId = utilityId,
            customerId = customerId,
            billPeriod = billPeriod,
            serviceReads = listOf(electricReads, waterReads),
            serviceTariffs = mapOf(
                ServiceType.ELECTRIC to electricTariff,
                ServiceType.WATER to waterTariff
            ),
            accountBalance = AccountBalance.zero(),
            regulatorySurcharges = emptyList(),
            contributions = emptyList()
        )
        
        val result = BillingEngine.calculateMultiServiceBill(input)
        
        // Verify service-specific charges exist
        val electricCharges = result.charges.filter { it.serviceType == ServiceType.ELECTRIC }
        val waterCharges = result.charges.filter { it.serviceType == ServiceType.WATER }
        
        assertTrue(electricCharges.isNotEmpty(), "Should have electric charges")
        assertTrue(waterCharges.isNotEmpty(), "Should have water charges")
        
        // Electric: $10 customer charge + (500 kWh * $0.12) = $10 + $60 = $70
        val electricTotal = electricCharges.sumOf { it.amount.amount }
        assertEquals(7000, electricTotal)
        
        // Water: $8 customer charge + (10 CCF * $3.00) = $8 + $30 = $38
        val waterTotal = waterCharges.sumOf { it.amount.amount }
        assertEquals(3800, waterTotal)
        
        // Total: $70 + $38 = $108
        assertEquals(Money(10800), result.totalCharges)
        assertEquals(Money(10800), result.amountDue)
    }
    
    @Test
    fun `multi-service bill applies regulatory surcharges correctly`() {
        val utilityId = UtilityId("mi-util")
        val customerId = CustomerId("cust-001")
        val billPeriod = BillingPeriod(
            id = "202501",
            utilityId = utilityId,
            startDate = LocalDate.of(2025, 1, 1),
            endDate = LocalDate.of(2025, 1, 31),
            billDate = LocalDate.of(2025, 2, 1),
            dueDate = LocalDate.of(2025, 2, 15),
            frequency = BillingFrequency.MONTHLY
        )
        
        val electricReads = ServiceMeterReads(
            serviceType = ServiceType.ELECTRIC,
            reads = listOf(
                MeterReadPair(
                    meterId = "ELEC-001",
                    serviceType = ServiceType.ELECTRIC,
                    usageType = UsageUnit.KWH,
                    startRead = MeterRead("ELEC-001", ServiceType.ELECTRIC, 0.0, LocalDate.of(2025, 1, 1), UsageUnit.KWH),
                    endRead = MeterRead("ELEC-001", ServiceType.ELECTRIC, 1000.0, LocalDate.of(2025, 1, 31), UsageUnit.KWH)
                )
            )
        )
        
        val electricTariff = RateTariff.FlatRate(
            readinessToServeCharge = Money(1000),
            ratePerUnit = Money(10),
            unit = "kWh"
        )
        
        val surcharges = listOf(
            MichiganElectricSurcharges.powerSupplyCostRecovery(),  // 2.5% of energy charges
            MichiganElectricSurcharges.systemAccessFee()            // $7.00 fixed
        )
        
        val input = MultiServiceBillInput(
            billId = BillId("BILL-001"),
            billRunId = BillRunId("RUN-001"),
            utilityId = utilityId,
            customerId = customerId,
            billPeriod = billPeriod,
            serviceReads = listOf(electricReads),
            serviceTariffs = mapOf(ServiceType.ELECTRIC to electricTariff),
            accountBalance = AccountBalance.zero(),
            regulatorySurcharges = surcharges,
            contributions = emptyList()
        )
        
        val result = BillingEngine.calculateMultiServiceBill(input)
        
        // Find surcharge charges
        val surchargeCharges = result.charges.filter { it.category == ChargeCategory.REGULATORY_CHARGE }
        
        assertEquals(2, surchargeCharges.size, "Should have two regulatory charges")
        
        // PSCR is $0.00125/kWh, so 1000 kWh * $0.00125 = $1.25
        val pscrCharge = surchargeCharges.find { it.code == "PSCR" }
        assertNotNull(pscrCharge)
        assertEquals(125000, pscrCharge?.amount?.amount)
        
        // SAF should be fixed $7.00
        val safCharge = surchargeCharges.find { it.code == "SAF" }
        assertNotNull(safCharge)
        assertEquals(700, safCharge?.amount?.amount)
    }
    
    @Test
    fun `multi-service bill includes voluntary contributions`() {
        val utilityId = UtilityId("mi-util")
        val customerId = CustomerId("cust-001")
        val billPeriod = BillingPeriod(
            id = "202501",
            utilityId = utilityId,
            startDate = LocalDate.of(2025, 1, 1),
            endDate = LocalDate.of(2025, 1, 31),
            billDate = LocalDate.of(2025, 2, 1),
            dueDate = LocalDate.of(2025, 2, 15),
            frequency = BillingFrequency.MONTHLY
        )
        
        val electricReads = ServiceMeterReads(
            serviceType = ServiceType.ELECTRIC,
            reads = listOf(
                MeterReadPair(
                    meterId = "ELEC-001",
                    serviceType = ServiceType.ELECTRIC,
                    usageType = UsageUnit.KWH,
                    startRead = MeterRead("ELEC-001", ServiceType.ELECTRIC, 0.0, LocalDate.of(2025, 1, 1), UsageUnit.KWH),
                    endRead = MeterRead("ELEC-001", ServiceType.ELECTRIC, 100.0, LocalDate.of(2025, 1, 31), UsageUnit.KWH)
                )
            )
        )
        
        val electricTariff = RateTariff.FlatRate(
            readinessToServeCharge = Money(1000),
            ratePerUnit = Money(10),
            unit = "kWh"
        )
        
        val contributions = listOf(
            VoluntaryContribution(
                code = "ENERGY_ASSIST",
                description = "Energy Assistance Program",
                amount = Money(500),
                program = ContributionProgram.ENERGY_ASSISTANCE
            ),
            VoluntaryContribution(
                code = "TREE_PLANT",
                description = "Urban Tree Planting",
                amount = Money(300),
                program = ContributionProgram.TREE_PLANTING
            )
        )
        
        val input = MultiServiceBillInput(
            billId = BillId("BILL-001"),
            billRunId = BillRunId("RUN-001"),
            utilityId = utilityId,
            customerId = customerId,
            billPeriod = billPeriod,
            serviceReads = listOf(electricReads),
            serviceTariffs = mapOf(ServiceType.ELECTRIC to electricTariff),
            accountBalance = AccountBalance.zero(),
            regulatorySurcharges = emptyList(),
            contributions = contributions
        )
        
        val result = BillingEngine.calculateMultiServiceBill(input)
        
        // Find contribution charges
        val contributionCharges = result.charges.filter { it.category == ChargeCategory.CONTRIBUTION }
        
        assertEquals(2, contributionCharges.size, "Should have two contribution charges")
        
        val energyAssist = contributionCharges.find { it.code == "ENERGY_ASSIST" }
        assertNotNull(energyAssist)
        assertEquals(Money(500), energyAssist?.amount)
        
        val treePlant = contributionCharges.find { it.code == "TREE_PLANT" }
        assertNotNull(treePlant)
        assertEquals(Money(300), treePlant?.amount)
        
        // Total should include contributions
        val contributionTotal = contributionCharges.sumOf { it.amount.amount }
        assertEquals(800, contributionTotal)
    }
    
    @Test
    fun `multi-service bill handles service without meter reads (flat-rate only)`() {
        val utilityId = UtilityId("mi-util")
        val customerId = CustomerId("cust-001")
        val billPeriod = BillingPeriod(
            id = "202501",
            utilityId = utilityId,
            startDate = LocalDate.of(2025, 1, 1),
            endDate = LocalDate.of(2025, 1, 31),
            billDate = LocalDate.of(2025, 2, 1),
            dueDate = LocalDate.of(2025, 2, 15),
            frequency = BillingFrequency.MONTHLY
        )
        
        // Broadband has no meter reads - flat monthly rate only
        val broadbandReads = ServiceMeterReads(
            serviceType = ServiceType.BROADBAND,
            reads = emptyList()
        )
        
        val broadbandTariff = RateTariff.FlatRate(
            readinessToServeCharge = Money(4999),  // $49.99/month
            ratePerUnit = Money(0),
            unit = ""
        )
        
        val input = MultiServiceBillInput(
            billId = BillId("BILL-001"),
            billRunId = BillRunId("RUN-001"),
            utilityId = utilityId,
            customerId = customerId,
            billPeriod = billPeriod,
            serviceReads = listOf(broadbandReads),
            serviceTariffs = mapOf(ServiceType.BROADBAND to broadbandTariff),
            accountBalance = AccountBalance.zero(),
            regulatorySurcharges = emptyList(),
            contributions = emptyList()
        )
        
        val result = BillingEngine.calculateMultiServiceBill(input)
        
        val broadbandCharges = result.charges.filter { it.serviceType == ServiceType.BROADBAND }
        
        assertEquals(1, broadbandCharges.size, "Should have one broadband charge")
        assertEquals(Money(4999), broadbandCharges.first().amount)
        assertEquals(Money(4999), result.totalCharges)
    }
}
