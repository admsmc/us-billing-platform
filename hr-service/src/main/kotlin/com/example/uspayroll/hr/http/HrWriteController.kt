package com.example.uspayroll.hr.http

import com.example.uspayroll.hr.audit.HrAuditService
import com.example.uspayroll.hr.employee.HrEmployeeRepository
import com.example.uspayroll.hr.idempotency.HrIdempotencyService
import com.example.uspayroll.hr.payperiod.HrPayPeriodRepository
import com.example.uspayroll.payroll.model.EmploymentType
import com.example.uspayroll.payroll.model.FilingStatus
import com.example.uspayroll.payroll.model.FlsaExemptStatus
import com.example.uspayroll.payroll.model.PayFrequency
import com.example.uspayroll.web.WebHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@RequestMapping("/employers/{employerId}")
class HrWriteController(
    private val employeeRepository: HrEmployeeRepository,
    private val payPeriodRepository: HrPayPeriodRepository,
    private val idempotency: HrIdempotencyService,
    private val audit: HrAuditService,
) {

    data class CreateEmployeeRequest(
        val firstName: String? = null,
        val lastName: String? = null,
        val profileEffectiveFrom: LocalDate,
        val homeState: String,
        val workState: String,
        val workCity: String? = null,
        val filingStatus: FilingStatus,
        val employmentType: EmploymentType = EmploymentType.REGULAR,
        val hireDate: LocalDate? = null,
        val terminationDate: LocalDate? = null,
        val dependents: Int? = null,
        val federalWithholdingExempt: Boolean = false,
        val isNonresidentAlien: Boolean = false,
        val w4AnnualCreditCents: Long? = null,
        val w4OtherIncomeCents: Long? = null,
        val w4DeductionsCents: Long? = null,
        val w4Step2MultipleJobs: Boolean = false,
        val w4Version: String? = null,
        val legacyAllowances: Int? = null,
        val legacyAdditionalWithholdingCents: Long? = null,
        val legacyMaritalStatus: String? = null,
        val w4EffectiveDate: LocalDate? = null,
        val additionalWithholdingCents: Long? = null,
        val ficaExempt: Boolean = false,
        val flsaEnterpriseCovered: Boolean = true,
        val flsaExemptStatus: FlsaExemptStatus = FlsaExemptStatus.NON_EXEMPT,
        val isTippedEmployee: Boolean = false,
    )

    @PutMapping("/employees/{employeeId}")
    fun createEmployee(
        @PathVariable employerId: String,
        @PathVariable employeeId: String,
        @RequestHeader(name = WebHeaders.IDEMPOTENCY_KEY, required = false) idempotencyKey: String?,
        @RequestBody req: CreateEmployeeRequest,
    ): ResponseEntity<Any> {
        val operation = "hr.employee.create"

        return idempotency.execute(
            employerId = employerId,
            operation = operation,
            idempotencyKey = idempotencyKey,
            requestBody = req,
        ) {
            if (employeeRepository.employeeExists(employerId, employeeId)) {
                ResponseEntity.status(HttpStatus.OK).body(
                    mapOf(
                        "status" to "EXISTS",
                        "employeeId" to employeeId,
                    ),
                )
            } else {
                employeeRepository.createEmployee(
                    HrEmployeeRepository.EmployeeCreate(
                        employerId = employerId,
                        employeeId = employeeId,
                        firstName = req.firstName,
                        lastName = req.lastName,
                        profileEffectiveFrom = req.profileEffectiveFrom,
                        homeState = req.homeState,
                        workState = req.workState,
                        workCity = req.workCity,
                        filingStatus = req.filingStatus,
                        employmentType = req.employmentType,
                        hireDate = req.hireDate,
                        terminationDate = req.terminationDate,
                        dependents = req.dependents,
                        federalWithholdingExempt = req.federalWithholdingExempt,
                        isNonresidentAlien = req.isNonresidentAlien,
                        w4AnnualCreditCents = req.w4AnnualCreditCents,
                        w4OtherIncomeCents = req.w4OtherIncomeCents,
                        w4DeductionsCents = req.w4DeductionsCents,
                        w4Step2MultipleJobs = req.w4Step2MultipleJobs,
                        w4Version = req.w4Version,
                        legacyAllowances = req.legacyAllowances,
                        legacyAdditionalWithholdingCents = req.legacyAdditionalWithholdingCents,
                        legacyMaritalStatus = req.legacyMaritalStatus,
                        w4EffectiveDate = req.w4EffectiveDate,
                        additionalWithholdingCents = req.additionalWithholdingCents,
                        ficaExempt = req.ficaExempt,
                        flsaEnterpriseCovered = req.flsaEnterpriseCovered,
                        flsaExemptStatus = req.flsaExemptStatus,
                        isTippedEmployee = req.isTippedEmployee,
                    ),
                )

                audit.record(
                    employerId = employerId,
                    entityType = "employee",
                    entityId = employeeId,
                    action = "CREATED",
                    effectiveFrom = req.profileEffectiveFrom,
                    before = null,
                    after = req,
                    ctx = HrAuditService.AuditContext(idempotencyKey = idempotencyKey),
                )

                ResponseEntity.status(HttpStatus.CREATED).body(
                    mapOf(
                        "status" to "CREATED",
                        "employeeId" to employeeId,
                    ),
                )
            }
        }
    }

    data class PatchEmployeeProfileRequest(
        val effectiveFrom: LocalDate,
        val homeState: String? = null,
        val workState: String? = null,
        val workCity: String? = null,
        val filingStatus: FilingStatus? = null,
        val employmentType: EmploymentType? = null,
        val hireDate: LocalDate? = null,
        val terminationDate: LocalDate? = null,
        val dependents: Int? = null,
        val federalWithholdingExempt: Boolean? = null,
        val isNonresidentAlien: Boolean? = null,
        val w4AnnualCreditCents: Long? = null,
        val w4OtherIncomeCents: Long? = null,
        val w4DeductionsCents: Long? = null,
        val w4Step2MultipleJobs: Boolean? = null,
        val w4Version: String? = null,
        val legacyAllowances: Int? = null,
        val legacyAdditionalWithholdingCents: Long? = null,
        val legacyMaritalStatus: String? = null,
        val w4EffectiveDate: LocalDate? = null,
        val additionalWithholdingCents: Long? = null,
        val ficaExempt: Boolean? = null,
        val flsaEnterpriseCovered: Boolean? = null,
        val flsaExemptStatus: FlsaExemptStatus? = null,
        val isTippedEmployee: Boolean? = null,
    )

    @PutMapping("/employees/{employeeId}/profile")
    fun patchEmployeeProfile(
        @PathVariable employerId: String,
        @PathVariable employeeId: String,
        @RequestHeader(name = WebHeaders.IDEMPOTENCY_KEY, required = false) idempotencyKey: String?,
        @RequestBody req: PatchEmployeeProfileRequest,
    ): ResponseEntity<Any> {
        val operation = "hr.employee.profile.patch"

        return idempotency.execute(
            employerId = employerId,
            operation = operation,
            idempotencyKey = idempotencyKey,
            requestBody = req,
        ) {
            val before = employeeRepository.findProfileAsOf(employerId, employeeId, req.effectiveFrom)
                ?: return@execute ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    mapOf(
                        "error" to "NOT_FOUND",
                        "employeeId" to employeeId,
                    ),
                )

            val updated = employeeRepository.patchProfileEffectiveDated(
                employerId,
                employeeId,
                HrEmployeeRepository.EmployeeProfilePatch(
                    effectiveFrom = req.effectiveFrom,
                    homeState = req.homeState,
                    workState = req.workState,
                    workCity = req.workCity,
                    filingStatus = req.filingStatus,
                    employmentType = req.employmentType,
                    hireDate = req.hireDate,
                    terminationDate = req.terminationDate,
                    dependents = req.dependents,
                    federalWithholdingExempt = req.federalWithholdingExempt,
                    isNonresidentAlien = req.isNonresidentAlien,
                    w4AnnualCreditCents = req.w4AnnualCreditCents,
                    w4OtherIncomeCents = req.w4OtherIncomeCents,
                    w4DeductionsCents = req.w4DeductionsCents,
                    w4Step2MultipleJobs = req.w4Step2MultipleJobs,
                    w4Version = req.w4Version,
                    legacyAllowances = req.legacyAllowances,
                    legacyAdditionalWithholdingCents = req.legacyAdditionalWithholdingCents,
                    legacyMaritalStatus = req.legacyMaritalStatus,
                    w4EffectiveDate = req.w4EffectiveDate,
                    additionalWithholdingCents = req.additionalWithholdingCents,
                    ficaExempt = req.ficaExempt,
                    flsaEnterpriseCovered = req.flsaEnterpriseCovered,
                    flsaExemptStatus = req.flsaExemptStatus,
                    isTippedEmployee = req.isTippedEmployee,
                ),
            )

            audit.record(
                employerId = employerId,
                entityType = "employee_profile",
                entityId = employeeId,
                action = "UPDATED",
                effectiveFrom = req.effectiveFrom,
                before = before,
                after = updated,
                ctx = HrAuditService.AuditContext(idempotencyKey = idempotencyKey),
            )

            ResponseEntity.ok(updated)
        }
    }

    data class UpsertCompensationRequest(
        val effectiveFrom: LocalDate,
        val compensationType: String,
        val annualSalaryCents: Long? = null,
        val hourlyRateCents: Long? = null,
        val payFrequency: PayFrequency,
        val effectiveTo: LocalDate = LocalDate.of(9999, 12, 31),
    )

    @PutMapping("/employees/{employeeId}/compensation")
    fun upsertCompensation(
        @PathVariable employerId: String,
        @PathVariable employeeId: String,
        @RequestHeader(name = WebHeaders.IDEMPOTENCY_KEY, required = false) idempotencyKey: String?,
        @RequestBody req: UpsertCompensationRequest,
    ): ResponseEntity<Any> {
        val operation = "hr.employee.compensation.upsert"

        return idempotency.execute(
            employerId = employerId,
            operation = operation,
            idempotencyKey = idempotencyKey,
            requestBody = req,
        ) {
            employeeRepository.upsertCompensationEffectiveDated(
                HrEmployeeRepository.CompensationCreate(
                    employerId = employerId,
                    employeeId = employeeId,
                    effectiveFrom = req.effectiveFrom,
                    effectiveTo = req.effectiveTo,
                    compensationType = req.compensationType,
                    annualSalaryCents = req.annualSalaryCents,
                    hourlyRateCents = req.hourlyRateCents,
                    payFrequency = req.payFrequency,
                ),
            )

            audit.record(
                employerId = employerId,
                entityType = "employment_compensation",
                entityId = employeeId,
                action = "UPSERTED",
                effectiveFrom = req.effectiveFrom,
                before = null,
                after = req,
                ctx = HrAuditService.AuditContext(idempotencyKey = idempotencyKey),
            )

            ResponseEntity.ok(mapOf("status" to "OK"))
        }
    }

    data class CreatePayPeriodRequest(
        val startDate: LocalDate,
        val endDate: LocalDate,
        val checkDate: LocalDate,
        val frequency: PayFrequency,
        val sequenceInYear: Int? = null,
    )

    @PutMapping("/pay-periods/{payPeriodId}")
    fun createPayPeriod(
        @PathVariable employerId: String,
        @PathVariable payPeriodId: String,
        @RequestHeader(name = WebHeaders.IDEMPOTENCY_KEY, required = false) idempotencyKey: String?,
        @RequestParam(name = "allowGaps", required = false, defaultValue = "true") allowGaps: Boolean,
        @RequestBody req: CreatePayPeriodRequest,
    ): ResponseEntity<Any> {
        val operation = "hr.pay_period.create"

        return idempotency.execute(
            employerId = employerId,
            operation = operation,
            idempotencyKey = idempotencyKey,
            requestBody = req,
        ) {
            val existing = payPeriodRepository.find(employerId, payPeriodId)
            if (existing != null) {
                // Idempotent behavior: if fields match, return OK; otherwise conflict.
                val same = existing.startDate == req.startDate &&
                    existing.endDate == req.endDate &&
                    existing.checkDate == req.checkDate &&
                    existing.frequency == req.frequency.name &&
                    existing.sequenceInYear == req.sequenceInYear

                if (same) {
                    return@execute ResponseEntity.status(HttpStatus.OK).body(mapOf("status" to "EXISTS"))
                }
                return@execute ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to "PAY_PERIOD_CONFLICT"))
            }

            // Validation.
            require(!req.endDate.isBefore(req.startDate)) { "endDate must be >= startDate" }
            require(!req.checkDate.isBefore(req.endDate)) { "checkDate must be >= endDate" }

            // Prevent overlapping periods.
            if (payPeriodRepository.hasOverlappingRange(employerId, req.startDate, req.endDate)) {
                return@execute ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to "PAY_PERIOD_OVERLAP"))
            }

            // Check-date uniqueness: avoid multiple pay periods resolving for the same check date.
            val byCheckDate = payPeriodRepository.findByCheckDate(employerId, req.checkDate)
            if (byCheckDate != null) {
                return@execute ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to "PAY_PERIOD_CHECK_DATE_CONFLICT"))
            }

            if (!allowGaps) {
                val prev = payPeriodRepository.findPreviousByEndDate(employerId, req.frequency, req.startDate)
                if (prev != null && prev.endDate.plusDays(1) != req.startDate) {
                    return@execute ResponseEntity.status(HttpStatus.CONFLICT).body(
                        mapOf(
                            "error" to "PAY_PERIOD_GAP",
                            "gapType" to "PREVIOUS",
                            "previousPayPeriodId" to prev.payPeriodId,
                        ),
                    )
                }

                val next = payPeriodRepository.findNextByStartDate(employerId, req.frequency, req.endDate)
                if (next != null && req.endDate.plusDays(1) != next.startDate) {
                    return@execute ResponseEntity.status(HttpStatus.CONFLICT).body(
                        mapOf(
                            "error" to "PAY_PERIOD_GAP",
                            "gapType" to "NEXT",
                            "nextPayPeriodId" to next.payPeriodId,
                        ),
                    )
                }
            }

            payPeriodRepository.create(
                HrPayPeriodRepository.PayPeriodCreate(
                    employerId = employerId,
                    payPeriodId = payPeriodId,
                    startDate = req.startDate,
                    endDate = req.endDate,
                    checkDate = req.checkDate,
                    frequency = req.frequency,
                    sequenceInYear = req.sequenceInYear,
                ),
            )

            audit.record(
                employerId = employerId,
                entityType = "pay_period",
                entityId = payPeriodId,
                action = "CREATED",
                effectiveFrom = req.checkDate,
                before = null,
                after = req,
                ctx = HrAuditService.AuditContext(idempotencyKey = idempotencyKey),
            )

            ResponseEntity.status(HttpStatus.CREATED).body(mapOf("status" to "CREATED"))
        }
    }
}
