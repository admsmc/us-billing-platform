package com.example.usbilling.payroll.model

import com.example.usbilling.payroll.model.garnishment.GarnishmentContext
import com.example.usbilling.shared.EmployeeId
import com.example.usbilling.shared.EmployerId
import com.example.usbilling.shared.PayRunId
import com.example.usbilling.shared.PaycheckId

// High-level input to the engine

data class PaycheckInput(
    val paycheckId: PaycheckId,
    val payRunId: PayRunId?,
    val employerId: EmployerId,
    val employeeId: EmployeeId,
    val period: PayPeriod,
    val employeeSnapshot: EmployeeSnapshot,
    val timeSlice: TimeSlice,
    val taxContext: TaxContext,
    val priorYtd: YtdSnapshot,
    /** Optional explicit pay schedule; if null, a default schedule is derived from the period frequency. */
    val paySchedule: PaySchedule? = null,
    /** Optional labor standards context (FLSA-style minimum wage, tip credit, etc.). */
    val laborStandards: LaborStandardsContext? = null,
    /** Active garnishment orders to consider for this paycheck. */
    val garnishments: GarnishmentContext = GarnishmentContext(),
)
