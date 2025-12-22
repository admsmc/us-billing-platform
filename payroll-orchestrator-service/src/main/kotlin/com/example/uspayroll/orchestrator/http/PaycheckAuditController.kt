package com.example.uspayroll.orchestrator.http

import com.example.uspayroll.orchestrator.persistence.PaycheckAuditStoreRepository
import com.example.uspayroll.payroll.model.audit.PaycheckAudit
import com.example.uspayroll.shared.EmployerId
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Internal/admin audit endpoint.
 *
 * Intentionally not part of the default paycheck retrieval API to avoid accidental
 * exposure of audit/compliance details.
 */
@RestController
@RequestMapping("/employers/{employerId}/paychecks/internal")
class PaycheckAuditController(
    private val paycheckAuditStoreRepository: PaycheckAuditStoreRepository,
) {

    @GetMapping("/{paycheckId}/audit")
    fun getAudit(
        @PathVariable employerId: String,
        @PathVariable paycheckId: String,
    ): ResponseEntity<PaycheckAudit> {
        val audit = paycheckAuditStoreRepository.findAudit(
            employerId = EmployerId(employerId),
            paycheckId = paycheckId,
        ) ?: return ResponseEntity.notFound().build()

        // Expose the full domain PaycheckAudit for internal/admin consumers.
        return ResponseEntity.ok(audit)
    }
}
