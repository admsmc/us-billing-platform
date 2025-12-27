package com.example.usbilling.payroll.model

import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import com.example.usbilling.shared.Money
import com.example.usbilling.shared.BillRunId
import com.example.usbilling.shared.BillId

// Output line items

data class EarningLine(
    val code: EarningCode,
    val category: EarningCategory,
    val description: String,
    val units: Double,
    val rate: Money?,
    val amount: Money,
)

data class TaxLine(
    val ruleId: String,
    val jurisdiction: TaxJurisdiction,
    val description: String,
    val basis: Money,
    val rate: Percent?,
    val amount: Money,
)

data class DeductionLine(
    val code: DeductionCode,
    val description: String,
    val amount: Money,
)

data class EmployerContributionLine(
    val code: EmployerContributionCode,
    val description: String,
    val amount: Money,
)

// Full paycheck result

data class PaycheckResult(
    val paycheckId: BillId,
    val payRunId: BillRunId?,
    val employerId: UtilityId,
    val employeeId: CustomerId,
    val period: PayPeriod,
    val earnings: List<EarningLine>,
    val employeeTaxes: List<TaxLine>,
    val employerTaxes: List<TaxLine>,
    val deductions: List<DeductionLine>,
    val employerContributions: List<EmployerContributionLine> = emptyList(),
    val gross: Money,
    val net: Money,
    val ytdAfter: YtdSnapshot,
    val trace: CalculationTrace = CalculationTrace(),
)
