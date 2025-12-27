package com.example.usbilling.payments.http

import com.example.usbilling.payments.persistence.PaycheckPaymentRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/employers/{employerId}")
class PaymentsController(
    private val repo: PaycheckPaymentRepository,
) {
    data class PaymentView(
        val employerId: String,
        val paymentId: String,
        val paycheckId: String,
        val payRunId: String,
        val employeeId: String,
        val payPeriodId: String,
        val currency: String,
        val netCents: Long,
        val status: String,
        val attempts: Int,
    )

    @GetMapping("/payruns/{payRunId}/payments")
    fun listForPayRun(@PathVariable employerId: String, @PathVariable payRunId: String): ResponseEntity<List<PaymentView>> {
        val rows = repo.listByPayRun(employerId, payRunId)
        return ResponseEntity.ok(
            rows.map {
                PaymentView(
                    employerId = it.employerId,
                    paymentId = it.paymentId,
                    paycheckId = it.paycheckId,
                    payRunId = it.payRunId,
                    employeeId = it.employeeId,
                    payPeriodId = it.payPeriodId,
                    currency = it.currency,
                    netCents = it.netCents,
                    status = it.status.name,
                    attempts = it.attempts,
                )
            },
        )
    }

    @GetMapping("/payments/by-paycheck/{paycheckId}")
    fun getByPaycheck(@PathVariable employerId: String, @PathVariable paycheckId: String): ResponseEntity<PaymentView> {
        val row = repo.findByPaycheck(employerId, paycheckId)
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(
            PaymentView(
                employerId = row.employerId,
                paymentId = row.paymentId,
                paycheckId = row.paycheckId,
                payRunId = row.payRunId,
                employeeId = row.employeeId,
                payPeriodId = row.payPeriodId,
                currency = row.currency,
                netCents = row.netCents,
                status = row.status.name,
                attempts = row.attempts,
            ),
        )
    }
}
