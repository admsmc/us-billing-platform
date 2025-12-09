package com.example.uspayroll.hr.api

import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.payroll.model.EmployeeSnapshot
import com.example.uspayroll.payroll.model.PayPeriod
import java.time.LocalDate

/**
 * Boundary interfaces exposed by the HR service.
 * These interfaces are implemented in hr-service and consumed by orchestrator/worker services.
 */

/** Provides effective-dated employee snapshots for payroll calculations. */
interface EmployeeSnapshotProvider {
    /**
     * Returns an employee snapshot as of the given date, suitable for a payroll period.
     */
    fun getEmployeeSnapshot(
        employerId: EmployerId,
        employeeId: EmployeeId,
        asOfDate: LocalDate,
    ): EmployeeSnapshot?
}

/** Provides pay periods and schedules for an employer. */
interface PayPeriodProvider {
    /**
     * Returns the pay period identified by [payPeriodId] for [employerId], or null if not found.
     */
    fun getPayPeriod(
        employerId: EmployerId,
        payPeriodId: String,
    ): PayPeriod?

    /**
     * Returns the active pay period for a given check date, if any.
     */
    fun findPayPeriodByCheckDate(
        employerId: EmployerId,
        checkDate: LocalDate,
    ): PayPeriod?
}
