package com.example.usbilling.worker.http

import com.example.usbilling.payroll.model.DeductionLine
import com.example.usbilling.payroll.model.EarningLine
import com.example.usbilling.payroll.model.EmployerContributionLine
import com.example.usbilling.payroll.model.PaycheckResult
import com.example.usbilling.payroll.model.TaxLine

/**
 * Benchmark-only statement rendering.
 *
 * This intentionally keeps business logic minimal: it converts a computed paycheck into
 * a "renderable" DTO and leaves formatting (PDF, templates, branding) to future work.
 */
object PayStatementRenderer {

    data class PayStatement(
        val paycheckId: String,
        val payRunId: String?,
        val employerId: String,
        val employeeId: String,
        val payPeriodId: String,
        val checkDate: String,
        val frequency: String,
        val grossCents: Long,
        val netCents: Long,
        val ytd: YtdDto,
        val earnings: List<EarningDto>,
        val employeeTaxes: List<TaxDto>,
        val employerTaxes: List<TaxDto>,
        val deductions: List<DeductionDto>,
        val employerContributions: List<EmployerContributionDto>,
    )

    data class YtdDto(
        val year: Int,
        val earningsCents: Long,
        /** Derived: earnings - employeeTaxes - deductions. */
        val netCents: Long,
        val employeeTaxesCents: Long,
        val employerTaxesCents: Long,
        val deductionsCents: Long,
        val employerContributionsCents: Long,
    )

    data class EarningDto(
        val code: String,
        val category: String,
        val description: String,
        val units: Double,
        val rateCents: Long?,
        val amountCents: Long,
    )

    data class TaxDto(
        val ruleId: String,
        val jurisdictionType: String,
        val jurisdictionCode: String,
        val description: String,
        val basisCents: Long,
        val rate: Double?,
        val amountCents: Long,
    )

    data class DeductionDto(
        val code: String,
        val description: String,
        val amountCents: Long,
    )

    data class EmployerContributionDto(
        val code: String,
        val description: String,
        val amountCents: Long,
    )

    fun render(p: PaycheckResult): PayStatement {
        val y = p.ytdAfter

        val ytdEarningsCents = y.earningsByCode.values.sumOf { it.amount }
        val ytdEmployeeTaxesCents = y.employeeTaxesByRuleId.values.sumOf { it.amount }
        val ytdEmployerTaxesCents = y.employerTaxesByRuleId.values.sumOf { it.amount }
        val ytdDeductionsCents = y.deductionsByCode.values.sumOf { it.amount }
        val ytdEmployerContribCents = y.employerContributionsByCode.values.sumOf { it.amount }
        val ytdNetCents = ytdEarningsCents - ytdEmployeeTaxesCents - ytdDeductionsCents

        return PayStatement(
            paycheckId = p.paycheckId.value,
            payRunId = p.payRunId?.value,
            employerId = p.employerId.value,
            employeeId = p.employeeId.value,
            payPeriodId = p.period.id,
            checkDate = p.period.checkDate.toString(),
            frequency = p.period.frequency.name,
            grossCents = p.gross.amount,
            netCents = p.net.amount,
            ytd = YtdDto(
                year = y.year,
                earningsCents = ytdEarningsCents,
                netCents = ytdNetCents,
                employeeTaxesCents = ytdEmployeeTaxesCents,
                employerTaxesCents = ytdEmployerTaxesCents,
                deductionsCents = ytdDeductionsCents,
                employerContributionsCents = ytdEmployerContribCents,
            ),
            earnings = p.earnings.map { it.toDto() },
            employeeTaxes = p.employeeTaxes.map { it.toDto() },
            employerTaxes = p.employerTaxes.map { it.toDto() },
            deductions = p.deductions.map { it.toDto() },
            employerContributions = p.employerContributions.map { it.toDto() },
        )
    }

    private fun EarningLine.toDto(): EarningDto = EarningDto(
        code = code.value,
        category = category.name,
        description = description,
        units = units,
        rateCents = rate?.amount,
        amountCents = amount.amount,
    )

    private fun TaxLine.toDto(): TaxDto = TaxDto(
        ruleId = ruleId,
        jurisdictionType = jurisdiction.type.name,
        jurisdictionCode = jurisdiction.code,
        description = description,
        basisCents = basis.amount,
        rate = rate?.value,
        amountCents = amount.amount,
    )

    private fun DeductionLine.toDto(): DeductionDto = DeductionDto(
        code = code.value,
        description = description,
        amountCents = amount.amount,
    )

    private fun EmployerContributionLine.toDto(): EmployerContributionDto = EmployerContributionDto(
        code = code.value,
        description = description,
        amountCents = amount.amount,
    )
}
