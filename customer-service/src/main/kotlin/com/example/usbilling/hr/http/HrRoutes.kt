package com.example.usbilling.hr.http

import com.example.usbilling.hr.api.EmployeeSnapshotProvider
import com.example.usbilling.hr.api.PayPeriodProvider
import com.example.usbilling.payroll.model.EmployeeSnapshot
import com.example.usbilling.payroll.model.PayPeriod
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
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
    ): EmployeeSnapshot? = employeeSnapshotProvider.getEmployeeSnapshot(
        employerId = UtilityId(employerId),
        employeeId = CustomerId(employeeId),
        asOfDate = asOf,
    )

    @GetMapping("/pay-periods/{payPeriodId}")
    fun getPayPeriod(@PathVariable employerId: String, @PathVariable payPeriodId: String): PayPeriod? = payPeriodProvider.getPayPeriod(
        employerId = UtilityId(employerId),
        payPeriodId = payPeriodId,
    )

    @GetMapping("/pay-periods/by-check-date")
    fun getPayPeriodByCheckDate(@PathVariable employerId: String, @RequestParam("checkDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) checkDate: LocalDate): PayPeriod? = payPeriodProvider.findPayPeriodByCheckDate(
        employerId = UtilityId(employerId),
        checkDate = checkDate,
    )
}
