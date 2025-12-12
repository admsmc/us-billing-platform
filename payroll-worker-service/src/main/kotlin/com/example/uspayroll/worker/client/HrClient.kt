package com.example.uspayroll.worker.client

import com.example.uspayroll.payroll.model.EmployeeSnapshot
import com.example.uspayroll.payroll.model.PayPeriod
import com.example.uspayroll.payroll.model.garnishment.GarnishmentOrder
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import java.time.LocalDate

/**
 * Client-side abstraction for talking to the HR service.
 * Implementations will typically use HTTP/gRPC to call hr-service.
 */
interface HrClient {
    fun getEmployeeSnapshot(employerId: EmployerId, employeeId: EmployeeId, asOfDate: LocalDate): EmployeeSnapshot?

    fun getPayPeriod(employerId: EmployerId, payPeriodId: String): PayPeriod?

    fun findPayPeriodByCheckDate(employerId: EmployerId, checkDate: LocalDate): PayPeriod?

    /**
     * Return all active garnishment orders for an employee as of the given
     * date.
     */
    fun getGarnishmentOrders(employerId: EmployerId, employeeId: EmployeeId, asOfDate: LocalDate): List<GarnishmentOrder>

    /**
     * Record the amounts withheld for one or more garnishment orders for a
     * single paycheck.
     */
    fun recordGarnishmentWithholding(employerId: EmployerId, employeeId: EmployeeId, request: GarnishmentWithholdingRequest)
}
