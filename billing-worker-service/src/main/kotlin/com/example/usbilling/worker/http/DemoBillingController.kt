package com.example.usbilling.worker.http

import com.example.usbilling.billing.engine.BillingEngine
import com.example.usbilling.billing.model.*
import com.example.usbilling.shared.*
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate

/**
 * Demo endpoint to prove billing-domain integration.
 * 
 * This controller demonstrates end-to-end integration of the new billing domain
 * with Spring Boot REST APIs.
 */
@RestController
@RequestMapping("/demo")
class DemoBillingController {
    
    @GetMapping("/bill")
    fun calculateDemoBill(): DemoBillResponse {
        // Create a sample bill input for a residential customer
        val input = BillInput(
            billId = BillId("DEMO-BILL-001"),
            billRunId = BillingCycleId("DEMO-RUN-2025-01"),
            utilityId = UtilityId("UTIL-DEMO"),
            customerId = CustomerId("CUST-DEMO-001"),
            billPeriod = BillingPeriod(
                id = "2025-01",
                utilityId = UtilityId("UTIL-DEMO"),
                startDate = LocalDate.of(2025, 1, 1),
                endDate = LocalDate.of(2025, 1, 31),
                billDate = LocalDate.of(2025, 2, 1),
                dueDate = LocalDate.of(2025, 2, 15),
                frequency = BillingFrequency.MONTHLY
            ),
            meterReads = listOf(
                MeterReadPair(
                    meterId = "MTR-DEMO-001",
                    serviceType = ServiceType.ELECTRIC,
                    usageType = UsageUnit.KWH,
                    startRead = MeterRead(
                        meterId = "MTR-DEMO-001",
                        serviceType = ServiceType.ELECTRIC,
                        readingValue = 10000.0,
                        readDate = LocalDate.of(2025, 1, 1),
                        usageUnit = UsageUnit.KWH
                    ),
                    endRead = MeterRead(
                        meterId = "MTR-DEMO-001",
                        serviceType = ServiceType.ELECTRIC,
                        readingValue = 10750.0, // 750 kWh usage
                        readDate = LocalDate.of(2025, 1, 31),
                        usageUnit = UsageUnit.KWH
                    )
                )
            ),
            rateTariff = RateTariff.FlatRate(
                readinessToServeCharge = Money(1500), // $15.00
                ratePerUnit = Money(12), // $0.12/kWh
                unit = "kWh"
            ),
            accountBalance = AccountBalance.zero()
        )
        
        // Calculate the bill using BillingEngine
        val result = BillingEngine.calculateBill(input)
        
        // Convert to response DTO
        return DemoBillResponse(
            billId = result.billId.value,
            customerId = result.customerId.value,
            billPeriod = "${result.billPeriod.startDate} to ${result.billPeriod.endDate}",
            charges = result.charges.map { charge ->
                ChargeDto(
                    code = charge.code,
                    description = charge.description,
                    amount = formatMoney(charge.amount),
                    usageAmount = charge.usageAmount,
                    usageUnit = charge.usageUnit
                )
            },
            totalCharges = formatMoney(result.totalCharges),
            totalCredits = formatMoney(result.totalCredits),
            amountDue = formatMoney(result.amountDue),
            dueDate = result.dueDate.toString()
        )
    }
    
    private fun formatMoney(money: Money): String {
        val dollars = money.amount / 100
        val cents = money.amount % 100
        return "$${dollars}.${cents.toString().padStart(2, '0')}"
    }
}

/**
 * Response DTO for demo bill endpoint.
 */
data class DemoBillResponse(
    val billId: String,
    val customerId: String,
    val billPeriod: String,
    val charges: List<ChargeDto>,
    val totalCharges: String,
    val totalCredits: String,
    val amountDue: String,
    val dueDate: String
)

/**
 * Charge line item DTO.
 */
data class ChargeDto(
    val code: String,
    val description: String,
    val amount: String,
    val usageAmount: Double?,
    val usageUnit: String?
)
