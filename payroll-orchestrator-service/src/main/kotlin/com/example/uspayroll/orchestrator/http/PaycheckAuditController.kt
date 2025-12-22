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

    data class PaycheckAuditDto(
        val schemaVersion: Int,
        val engineVersion: String,
        val computedAt: java.time.Instant,
        val employerId: String,
        val employeeId: String,
        val paycheckId: String,
        val payRunId: String?,
        val payPeriodId: String,
        val checkDate: java.time.LocalDate,
        val cashGrossCents: Long,
        val grossTaxableCents: Long,
        val federalTaxableCents: Long,
        val stateTaxableCents: Long,
        val socialSecurityWagesCents: Long,
        val medicareWagesCents: Long,
        val supplementalWagesCents: Long,
        val futaWagesCents: Long,
        val employeeTaxCents: Long,
        val employerTaxCents: Long,
        val preTaxDeductionCents: Long,
        val postTaxDeductionCents: Long,
        val garnishmentCents: Long,
        val netCents: Long,
    ) {
        companion object {
            fun fromDomain(a: PaycheckAudit): PaycheckAuditDto = PaycheckAuditDto(
                schemaVersion = a.schemaVersion,
                engineVersion = a.engineVersion,
                computedAt = a.computedAt,
                employerId = a.employerId,
                employeeId = a.employeeId,
                paycheckId = a.paycheckId,
                payRunId = a.payRunId,
                payPeriodId = a.payPeriodId,
                checkDate = a.checkDate,
                cashGrossCents = a.cashGrossCents,
                grossTaxableCents = a.grossTaxableCents,
                federalTaxableCents = a.federalTaxableCents,
                stateTaxableCents = a.stateTaxableCents,
                socialSecurityWagesCents = a.socialSecurityWagesCents,
                medicareWagesCents = a.medicareWagesCents,
                supplementalWagesCents = a.supplementalWagesCents,
                futaWagesCents = a.futaWagesCents,
                employeeTaxCents = a.employeeTaxCents,
                employerTaxCents = a.employerTaxCents,
                preTaxDeductionCents = a.preTaxDeductionCents,
                postTaxDeductionCents = a.postTaxDeductionCents,
                garnishmentCents = a.garnishmentCents,
                netCents = a.netCents,
            )
        }
    }

    @GetMapping("/{paycheckId}/audit")
    fun getAudit(
        @PathVariable employerId: String,
        @PathVariable paycheckId: String,
    ): ResponseEntity<PaycheckAuditDto> {
        val audit = paycheckAuditStoreRepository.findAudit(
            employerId = EmployerId(employerId),
            paycheckId = paycheckId,
        ) ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(PaycheckAuditDto.fromDomain(audit))
    }
}
