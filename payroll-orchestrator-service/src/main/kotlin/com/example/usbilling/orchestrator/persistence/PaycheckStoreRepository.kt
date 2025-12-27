package com.example.usbilling.orchestrator.persistence

import com.example.usbilling.payroll.model.PaycheckResult
import com.example.usbilling.shared.UtilityId

interface PaycheckStoreRepository {
    fun insertFinalPaycheckIfAbsent(
        employerId: UtilityId,
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

    fun findPaycheck(employerId: UtilityId, paycheckId: String): PaycheckResult?

    fun findCorrectionOfBillId(employerId: UtilityId, paycheckId: String): String?

    /**
     * Attach a correction linkage to a paycheck if unset.
     */
    fun setCorrectionOfPaycheckIdIfNull(employerId: UtilityId, paycheckId: String, correctionOfPaycheckId: String): Boolean
}
