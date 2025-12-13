package com.example.uspayroll.payroll.engine

import com.example.uspayroll.payroll.model.EmployerContributionLine
import com.example.uspayroll.payroll.model.PaycheckInput
import com.example.uspayroll.payroll.model.PaycheckResult
import com.example.uspayroll.payroll.model.audit.TraceLevel
import com.example.uspayroll.payroll.model.config.DeductionConfigRepository
import com.example.uspayroll.payroll.model.config.EarningConfigRepository
import com.example.uspayroll.payroll.model.garnishment.SupportCapContext
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
