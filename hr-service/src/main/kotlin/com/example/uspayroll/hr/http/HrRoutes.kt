package com.example.uspayroll.hr.http

import com.example.uspayroll.hr.api.EmployeeSnapshotProvider
import com.example.uspayroll.hr.api.PayPeriodProvider
import com.example.uspayroll.payroll.model.EmployeeSnapshot
import com.example.uspayroll.payroll.model.PayPeriod
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@RequestMapping("/employers/{employerId}")
class HrHttpController(
    private val employeeSnapshotProvider: EmployeeSnapshotProvider,
    private val payPeriodProvider: PayPeriodProvider,
) {

    @GetMapping("/employees/{employeeId}/snapshot")
    fun getEmployeeSnapshot(
        @PathVariable employerId: String,
        @PathVariable employeeId: String,
        @RequestParam("asOf") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) asOf: LocalDate,
    ): EmployeeSnapshot? {
        return employeeSnapshotProvider.getEmployeeSnapshot(
            employerId = EmployerId(employerId),
            employeeId = EmployeeId(employeeId),
            asOfDate = asOf,
        )
    }

    @GetMapping("/pay-periods/{payPeriodId}")
    fun getPayPeriod(@PathVariable employerId: String, @PathVariable payPeriodId: String): PayPeriod? {
        return payPeriodProvider.getPayPeriod(
            employerId = EmployerId(employerId),
            payPeriodId = payPeriodId,
        )
    }
}
