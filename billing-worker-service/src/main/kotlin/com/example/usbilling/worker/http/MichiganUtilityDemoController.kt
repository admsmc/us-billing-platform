package com.example.usbilling.worker.http

import com.example.usbilling.billing.engine.BillingEngine
import com.example.usbilling.billing.model.*
import com.example.usbilling.billing.model.michigan.MichiganElectricSurcharges
import com.example.usbilling.billing.model.michigan.MichiganWaterSurcharges
import com.example.usbilling.shared.*
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/**
 * Demo controller showing Michigan public utility multi-service billing.
 */
@RestController
@RequestMapping("/demo")
class MichiganUtilityDemoController {
    
    /**
     * Demo: Michigan residential customer with electric, water, wastewater, broadband services
     * plus a voluntary energy assistance donation.
     */
    @GetMapping("/michigan-multi-service-bill")
    fun michiganMultiServiceBill(): BillResult {
        val utilityId = UtilityId("michigan-utility-001")
        val customerId = CustomerId("customer-12345")
        val billPeriod = BillingPeriod(
            id = "202512",
            utilityId = utilityId,
            startDate = LocalDate.of(2025, 12, 1),
            endDate = LocalDate.of(2025, 12, 31),
            billDate = LocalDate.of(2025, 12, 31),
            dueDate = LocalDate.of(2026, 1, 20),
            frequency = BillingFrequency.MONTHLY
        )
        
        // Electric service: 800 kWh with tiered rates
        val electricReads = ServiceMeterReads(
            serviceType = ServiceType.ELECTRIC,
            reads = listOf(
                MeterReadPair(
                    meterId = "ELEC-METER-001",
                    serviceType = ServiceType.ELECTRIC,
                    usageType = UsageUnit.KWH,
                    startRead = MeterRead(
                        meterId = "ELEC-METER-001",
                        serviceType = ServiceType.ELECTRIC,
                        readingValue = 45200.0,
                        readDate = LocalDate.of(2025, 12, 1),
                        usageUnit = UsageUnit.KWH
                    ),
                    endRead = MeterRead(
                        meterId = "ELEC-METER-001",
                        serviceType = ServiceType.ELECTRIC,
                        readingValue = 46000.0,
                        readDate = LocalDate.of(2025, 12, 31),
                        usageUnit = UsageUnit.KWH
                    )
                )
            )
        )
        
        // Water service: 15 CCF
        val waterReads = ServiceMeterReads(
            serviceType = ServiceType.WATER,
            reads = listOf(
                MeterReadPair(
                    meterId = "WATER-METER-001",
                    serviceType = ServiceType.WATER,
                    usageType = UsageUnit.CCF,
                    startRead = MeterRead(
                        meterId = "WATER-METER-001",
                        serviceType = ServiceType.WATER,
                        readingValue = 1250.0,
                        readDate = LocalDate.of(2025, 12, 1),
                        usageUnit = UsageUnit.CCF
                    ),
                    endRead = MeterRead(
                        meterId = "WATER-METER-001",
                        serviceType = ServiceType.WATER,
                        readingValue = 1265.0,
                        readDate = LocalDate.of(2025, 12, 31),
                        usageUnit = UsageUnit.CCF
                    )
                )
            )
        )
        
        // Wastewater service: 15 CCF (typically same as water)
        val wastewaterReads = ServiceMeterReads(
            serviceType = ServiceType.WASTEWATER,
            reads = listOf(
                MeterReadPair(
                    meterId = "WATER-METER-001",  // Often uses water meter reading
                    serviceType = ServiceType.WASTEWATER,
                    usageType = UsageUnit.CCF,
                    startRead = MeterRead(
                        meterId = "WATER-METER-001",
                        serviceType = ServiceType.WASTEWATER,
                        readingValue = 1250.0,
                        readDate = LocalDate.of(2025, 12, 1),
                        usageUnit = UsageUnit.CCF
                    ),
                    endRead = MeterRead(
                        meterId = "WATER-METER-001",
                        serviceType = ServiceType.WASTEWATER,
                        readingValue = 1265.0,
                        readDate = LocalDate.of(2025, 12, 31),
                        usageUnit = UsageUnit.CCF
                    )
                )
            )
        )
        
        // Broadband service: 100 Mbps plan (no metered usage)
        val broadbandReads = ServiceMeterReads(
            serviceType = ServiceType.BROADBAND,
            reads = emptyList()  // Broadband is flat-rate, no metered reads
        )
        
        // Electric tariff: Tiered residential rate
        val electricTariff = RateTariff.TieredRate(
            readinessToServeCharge = Money(1500),  // $15.00/month
            tiers = listOf(
                RateTier(maxUsage = 500.0, ratePerUnit = Money(10)),     // $0.10/kWh for first 500 kWh
                RateTier(maxUsage = null, ratePerUnit = Money(12))        // $0.12/kWh above 500 kWh
            ),
            unit = "kWh"
        )
        
        // Water tariff: Flat rate
        val waterTariff = RateTariff.FlatRate(
            readinessToServeCharge = Money(800),  // $8.00/month
            ratePerUnit = Money(350),      // $3.50/CCF
            unit = "CCF"
        )
        
        // Wastewater tariff: Flat rate
        val wastewaterTariff = RateTariff.FlatRate(
            readinessToServeCharge = Money(800),  // $8.00/month
            ratePerUnit = Money(400),      // $4.00/CCF
            unit = "CCF"
        )
        
        // Broadband tariff: Flat monthly rate
        val broadbandTariff = RateTariff.FlatRate(
            readinessToServeCharge = Money(4999),  // $49.99/month for 100 Mbps
            ratePerUnit = Money(0),         // No per-unit charge
            unit = ""
        )
        
        // Michigan regulatory surcharges
        val surcharges = listOf(
            MichiganElectricSurcharges.powerSupplyCostRecovery(),
            MichiganElectricSurcharges.systemAccessFee(),
            MichiganElectricSurcharges.liheapSurcharge(),
            MichiganWaterSurcharges.infrastructureCharge()
        )
        
        // Voluntary contribution
        val contributions = listOf(
            VoluntaryContribution(
                code = "ENERGY_ASSIST",
                description = "Energy Assistance Program",
                amount = Money(500),  // $5.00 donation
                program = ContributionProgram.ENERGY_ASSISTANCE
            )
        )
        
        val input = MultiServiceBillInput(
            billId = BillId("BILL-202512-12345"),
            billRunId = BillingCycleId("RUN-202512"),
            utilityId = utilityId,
            customerId = customerId,
            billPeriod = billPeriod,
            serviceReads = listOf(electricReads, waterReads, wastewaterReads, broadbandReads),
            serviceTariffs = mapOf(
                ServiceType.ELECTRIC to electricTariff,
                ServiceType.WATER to waterTariff,
                ServiceType.WASTEWATER to wastewaterTariff,
                ServiceType.BROADBAND to broadbandTariff
            ),
            accountBalance = AccountBalance.zero(),
            regulatorySurcharges = surcharges,
            contributions = contributions
        )
        
        return BillingEngine.calculateMultiServiceBill(input)
    }
}
