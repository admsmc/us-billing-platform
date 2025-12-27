package com.example.usbilling.orchestrator.persistence

import com.example.usbilling.payroll.model.PaycheckResult
import com.example.usbilling.shared.EmployerId

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

    fun findCorrectionOfPaycheckId(employerId: EmployerId, paycheckId: String): String?

    /**
     * Attach a correction linkage to a paycheck if unset.
     */
    fun setCorrectionOfPaycheckIdIfNull(employerId: EmployerId, paycheckId: String, correctionOfPaycheckId: String): Boolean
}
