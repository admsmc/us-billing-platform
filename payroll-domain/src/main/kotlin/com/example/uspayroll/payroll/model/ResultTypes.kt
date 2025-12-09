package com.example.uspayroll.payroll.model

import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money
import com.example.uspayroll.shared.PaycheckId
import com.example.uspayroll.shared.PayRunId

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
    val paycheckId: PaycheckId,
    val payRunId: PayRunId?,
    val employerId: EmployerId,
    val employeeId: EmployeeId,
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
