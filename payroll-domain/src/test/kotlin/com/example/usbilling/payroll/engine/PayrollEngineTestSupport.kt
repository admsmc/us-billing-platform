package com.example.usbilling.payroll.engine

import com.example.usbilling.payroll.model.EmployerContributionLine
import com.example.usbilling.payroll.model.PaycheckInput
import com.example.usbilling.payroll.model.PaycheckResult
import com.example.usbilling.payroll.model.audit.TraceLevel
import com.example.usbilling.payroll.model.config.DeductionConfigRepository
import com.example.usbilling.payroll.model.config.EarningConfigRepository
import com.example.usbilling.payroll.model.garnishment.SupportCapContext
import java.time.Instant

/**
 * Prefer using this helper in tests that assert on trace internals.
 */
fun calculatePaycheckDebug(
    input: PaycheckInput,
    earningConfig: EarningConfigRepository? = null,
    deductionConfig: DeductionConfigRepository? = null,
    overtimePolicy: OvertimePolicy = OvertimePolicy.Default,
    employerContributions: List<EmployerContributionLine> = emptyList(),
    strictYtdYear: Boolean = false,
    supportCapContext: SupportCapContext? = null,
): PaycheckResult = PayrollEngine.calculatePaycheckComputation(
    input = input,
    computedAt = Instant.EPOCH,
    traceLevel = TraceLevel.DEBUG,
    earningConfig = earningConfig,
    deductionConfig = deductionConfig,
    overtimePolicy = overtimePolicy,
    employerContributions = employerContributions,
    strictYtdYear = strictYtdYear,
    supportCapContext = supportCapContext,
).paycheck
