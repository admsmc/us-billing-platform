package com.example.usbilling.payroll.model

import com.example.usbilling.payroll.model.garnishment.GarnishmentContext
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import com.example.usbilling.shared.BillRunId
import com.example.usbilling.shared.BillId

// High-level input to the engine

data class PaycheckInput(
    val paycheckId: BillId,
    val payRunId: BillRunId?,
    val employerId: UtilityId,
    val employeeId: CustomerId,
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
