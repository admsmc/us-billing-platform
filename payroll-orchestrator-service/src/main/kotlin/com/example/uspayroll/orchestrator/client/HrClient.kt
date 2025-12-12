package com.example.uspayroll.orchestrator.client

import com.example.uspayroll.payroll.model.EmployeeSnapshot
import com.example.uspayroll.payroll.model.PayPeriod
import com.example.uspayroll.payroll.model.garnishment.GarnishmentOrder
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import java.time.LocalDate

interface HrClient {
    fun getEmployeeSnapshot(
        employerId: EmployerId,
        employeeId: EmployeeId,
        asOfDate: LocalDate,
    ): EmployeeSnapshot?

    fun getPayPeriod(
        employerId: EmployerId,
        payPeriodId: String,
    ): PayPeriod?

    fun getGarnishmentOrders(
        employerId: EmployerId,
        employeeId: EmployeeId,
        asOfDate: LocalDate,
    ): List<GarnishmentOrder>
}
