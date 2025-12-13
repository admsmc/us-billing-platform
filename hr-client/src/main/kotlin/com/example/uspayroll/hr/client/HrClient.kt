package com.example.uspayroll.hr.client

import com.example.uspayroll.hr.http.GarnishmentWithholdingRequest
import com.example.uspayroll.payroll.model.EmployeeSnapshot
import com.example.uspayroll.payroll.model.PayPeriod
import com.example.uspayroll.payroll.model.garnishment.GarnishmentOrder
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import java.time.LocalDate

/**
 * Client-side abstraction for talking to hr-service.
 *
 * This interface is intentionally transport-agnostic (HTTP/gRPC/etc). Concrete
 * adapters live in service modules.
 */
interface HrClient {
    fun getEmployeeSnapshot(employerId: EmployerId, employeeId: EmployeeId, asOfDate: LocalDate): EmployeeSnapshot?

    fun getPayPeriod(employerId: EmployerId, payPeriodId: String): PayPeriod?

    fun findPayPeriodByCheckDate(employerId: EmployerId, checkDate: LocalDate): PayPeriod?

    fun getGarnishmentOrders(employerId: EmployerId, employeeId: EmployeeId, asOfDate: LocalDate): List<GarnishmentOrder>

    fun recordGarnishmentWithholding(employerId: EmployerId, employeeId: EmployeeId, request: GarnishmentWithholdingRequest)
}
