package com.example.usbilling.hr.client

import com.example.usbilling.hr.http.GarnishmentWithholdingRequest
import com.example.usbilling.payroll.model.EmployeeSnapshot
import com.example.usbilling.payroll.model.PayPeriod
import com.example.usbilling.payroll.model.garnishment.GarnishmentOrder
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import java.time.LocalDate

/**
 * Client-side abstraction for talking to hr-service.
 *
 * This interface is intentionally transport-agnostic (HTTP/gRPC/etc). Concrete
 * adapters live in service modules.
 */
interface HrClient {
    fun getEmployeeSnapshot(employerId: UtilityId, employeeId: CustomerId, asOfDate: LocalDate): EmployeeSnapshot?

    fun getPayPeriod(employerId: UtilityId, payPeriodId: String): PayPeriod?

    fun findPayPeriodByCheckDate(employerId: UtilityId, checkDate: LocalDate): PayPeriod?

    fun getGarnishmentOrders(employerId: UtilityId, employeeId: CustomerId, asOfDate: LocalDate): List<GarnishmentOrder>

    fun recordGarnishmentWithholding(employerId: UtilityId, employeeId: CustomerId, request: GarnishmentWithholdingRequest)
}
