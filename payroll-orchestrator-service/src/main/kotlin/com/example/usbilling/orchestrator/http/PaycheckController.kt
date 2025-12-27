package com.example.usbilling.orchestrator.http

import com.example.usbilling.orchestrator.persistence.PaycheckStoreRepository
import com.example.usbilling.payroll.model.PaycheckResult
import com.example.usbilling.shared.EmployerId
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/employers/{employerId}/paychecks")
class PaycheckController(
    private val paycheckStoreRepository: PaycheckStoreRepository,
) {

    @GetMapping("/{paycheckId}")
    fun getPaycheck(
        @PathVariable employerId: String,
        @PathVariable paycheckId: String,
    ): ResponseEntity<PaycheckResult> {
        val paycheck = paycheckStoreRepository.findPaycheck(
            employerId = EmployerId(employerId),
            paycheckId = paycheckId,
        ) ?: return ResponseEntity.notFound().build()

        // Expose the full domain PaycheckResult for internal/reporting consumers.
        return ResponseEntity.ok(paycheck)
    }
}
