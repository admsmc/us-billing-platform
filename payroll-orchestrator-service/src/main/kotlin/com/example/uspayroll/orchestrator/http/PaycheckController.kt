package com.example.uspayroll.orchestrator.http

import com.example.uspayroll.orchestrator.persistence.PaycheckStoreRepository
import com.example.uspayroll.payroll.model.PaycheckResult
import com.example.uspayroll.shared.EmployerId
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

        return ResponseEntity.ok(paycheck)
    }
}
