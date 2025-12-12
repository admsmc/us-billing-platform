package com.example.uspayroll.orchestrator.persistence

import com.example.uspayroll.payroll.model.PaycheckResult
import com.example.uspayroll.shared.EmployerId

interface PaycheckStoreRepository {
    fun insertFinalPaycheckIfAbsent(
        employerId: EmployerId,
        paycheckId: String,
        payRunId: String,
        employeeId: String,
        payPeriodId: String,
        runType: String,
        runSequence: Int,
        checkDateIso: String,
        grossCents: Long,
        netCents: Long,
        version: Int,
        payload: PaycheckResult,
    )

    fun findPaycheck(employerId: EmployerId, paycheckId: String): PaycheckResult?
}
