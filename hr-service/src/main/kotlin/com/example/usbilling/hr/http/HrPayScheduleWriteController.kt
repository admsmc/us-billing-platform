package com.example.usbilling.hr.http

import com.example.usbilling.hr.audit.HrAuditService
import com.example.usbilling.hr.idempotency.HrIdempotencyService
import com.example.usbilling.hr.payperiod.HrPayPeriodRepository
import com.example.usbilling.hr.payperiod.HrPayScheduleRepository
import com.example.usbilling.hr.payperiod.PayPeriodGeneration
import com.example.usbilling.payroll.model.PayFrequency
import com.example.usbilling.web.WebHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@RequestMapping("/employers/{employerId}")
class HrPayScheduleWriteController(
    private val scheduleRepository: HrPayScheduleRepository,
    private val payPeriodRepository: HrPayPeriodRepository,
    private val idempotency: HrIdempotencyService,
    private val audit: HrAuditService,
) {

    data class UpsertPayScheduleRequest(
        val frequency: PayFrequency,
        val firstStartDate: LocalDate,
        val checkDateOffsetDays: Int = 0,
        val semiMonthlyFirstEndDay: Int? = null,
    )

    @PutMapping("/pay-schedules/{scheduleId}")
    fun upsertPaySchedule(
        @PathVariable employerId: String,
        @PathVariable scheduleId: String,
        @RequestHeader(name = WebHeaders.IDEMPOTENCY_KEY, required = false) idempotencyKey: String?,
        @RequestBody req: UpsertPayScheduleRequest,
    ): ResponseEntity<Any> {
        val operation = "hr.pay_schedule.upsert"

        return idempotency.execute(
            employerId = employerId,
            operation = operation,
            idempotencyKey = idempotencyKey,
            requestBody = req,
        ) {
            require(req.checkDateOffsetDays >= 0) { "checkDateOffsetDays must be >= 0" }
            if (req.frequency == PayFrequency.SEMI_MONTHLY) {
                requireNotNull(req.semiMonthlyFirstEndDay) { "semiMonthlyFirstEndDay is required for SEMI_MONTHLY" }
            }

            val existing = scheduleRepository.find(employerId, scheduleId)
            if (existing != null) {
                val same = existing.frequency == req.frequency.name &&
                    existing.firstStartDate == req.firstStartDate &&
                    existing.checkDateOffsetDays == req.checkDateOffsetDays &&
                    existing.semiMonthlyFirstEndDay == req.semiMonthlyFirstEndDay

                if (same) {
                    return@execute ResponseEntity.status(HttpStatus.OK).body(mapOf("status" to "EXISTS"))
                }
            }

            scheduleRepository.upsert(
                HrPayScheduleRepository.PayScheduleUpsert(
                    employerId = employerId,
                    scheduleId = scheduleId,
                    frequency = req.frequency,
                    firstStartDate = req.firstStartDate,
                    checkDateOffsetDays = req.checkDateOffsetDays,
                    semiMonthlyFirstEndDay = req.semiMonthlyFirstEndDay,
                ),
            )

            audit.record(
                employerId = employerId,
                entityType = "pay_schedule",
                entityId = scheduleId,
                action = if (existing == null) "CREATED" else "UPDATED",
                effectiveFrom = req.firstStartDate,
                before = existing,
                after = req,
                ctx = HrAuditService.AuditContext(idempotencyKey = idempotencyKey),
            )

            ResponseEntity.status(if (existing == null) HttpStatus.CREATED else HttpStatus.OK)
                .body(mapOf("status" to if (existing == null) "CREATED" else "UPDATED"))
        }
    }

    data class GeneratePayPeriodsRequest(
        val year: Int,
        val dryRun: Boolean,
    )

    @PostMapping("/pay-schedules/{scheduleId}/generate-pay-periods")
    fun generatePayPeriods(
        @PathVariable employerId: String,
        @PathVariable scheduleId: String,
        @RequestHeader(name = WebHeaders.IDEMPOTENCY_KEY, required = false) idempotencyKey: String?,
        @RequestParam("year") year: Int,
        @RequestParam("dryRun", required = false, defaultValue = "false") dryRun: Boolean,
        @RequestParam(name = "allowGaps", required = false, defaultValue = "true") allowGaps: Boolean,
    ): ResponseEntity<Any> {
        val operation = "hr.pay_schedule.generate_pay_periods"
        val requestBody = GeneratePayPeriodsRequest(year = year, dryRun = dryRun)

        return idempotency.execute(
            employerId = employerId,
            operation = operation,
            idempotencyKey = idempotencyKey,
            requestBody = requestBody,
        ) {
            val scheduleRow = scheduleRepository.find(employerId, scheduleId)
                ?: return@execute ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "PAY_SCHEDULE_NOT_FOUND"))

            val schedule = PayPeriodGeneration.PaySchedule(
                employerId = employerId,
                scheduleId = scheduleId,
                frequency = PayFrequency.valueOf(scheduleRow.frequency),
                firstStartDate = scheduleRow.firstStartDate,
                checkDateOffsetDays = scheduleRow.checkDateOffsetDays,
                semiMonthlyFirstEndDay = scheduleRow.semiMonthlyFirstEndDay,
            )

            val generated = PayPeriodGeneration.generateForYear(schedule, year)
                .sortedBy { it.startDate }

            // Pay schedules imply contiguous coverage for the generated set.
            for (i in 1 until generated.size) {
                val prev = generated[i - 1]
                val curr = generated[i]
                require(prev.endDate.plusDays(1) == curr.startDate) {
                    "Generated pay periods contain a gap between ${prev.payPeriodId} and ${curr.payPeriodId}"
                }
            }

            if (!allowGaps && generated.isNotEmpty()) {
                val first = generated.first()
                val prevExisting = payPeriodRepository.findPreviousByEndDate(employerId, schedule.frequency, first.startDate)
                if (prevExisting != null && prevExisting.endDate.plusDays(1) != first.startDate) {
                    return@execute ResponseEntity.status(HttpStatus.CONFLICT).body(
                        mapOf(
                            "error" to "PAY_PERIOD_GAP",
                            "gapType" to "PREVIOUS",
                            "previousPayPeriodId" to prevExisting.payPeriodId,
                            "newPayPeriodId" to first.payPeriodId,
                        ),
                    )
                }

                val last = generated.last()
                val nextExisting = payPeriodRepository.findNextByStartDate(employerId, schedule.frequency, last.endDate)
                if (nextExisting != null && last.endDate.plusDays(1) != nextExisting.startDate) {
                    return@execute ResponseEntity.status(HttpStatus.CONFLICT).body(
                        mapOf(
                            "error" to "PAY_PERIOD_GAP",
                            "gapType" to "NEXT",
                            "nextPayPeriodId" to nextExisting.payPeriodId,
                            "newPayPeriodId" to last.payPeriodId,
                        ),
                    )
                }
            }

            var created = 0
            val createdIds = mutableListOf<String>()
            generated.forEach { g ->
                // Validate basic invariants.
                require(!g.endDate.isBefore(g.startDate)) { "endDate must be >= startDate" }
                require(!g.checkDate.isBefore(g.endDate)) { "checkDate must be >= endDate" }

                val existingById = payPeriodRepository.find(employerId, g.payPeriodId)
                if (existingById != null) {
                    val same = existingById.startDate == g.startDate &&
                        existingById.endDate == g.endDate &&
                        existingById.checkDate == g.checkDate &&
                        existingById.frequency == g.frequency.name &&
                        existingById.sequenceInYear == g.sequenceInYear

                    if (!same) {
                        return@execute ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(mapOf("error" to "PAY_PERIOD_CONFLICT", "payPeriodId" to g.payPeriodId))
                    }
                    return@forEach
                }

                // Prevent overlaps with any existing pay period range.
                if (payPeriodRepository.hasOverlappingRange(employerId, g.startDate, g.endDate)) {
                    return@execute ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(mapOf("error" to "PAY_PERIOD_OVERLAP", "payPeriodId" to g.payPeriodId))
                }

                // Check-date uniqueness: avoid multiple periods resolving for the same check date.
                val existingByCheckDate = payPeriodRepository.findByCheckDate(employerId, g.checkDate)
                if (existingByCheckDate != null) {
                    return@execute ResponseEntity.status(HttpStatus.CONFLICT).body(
                        mapOf(
                            "error" to "PAY_PERIOD_CHECK_DATE_CONFLICT",
                            "checkDate" to g.checkDate,
                            "existingPayPeriodId" to existingByCheckDate.payPeriodId,
                            "newPayPeriodId" to g.payPeriodId,
                        ),
                    )
                }

                if (!dryRun) {
                    payPeriodRepository.create(
                        HrPayPeriodRepository.PayPeriodCreate(
                            employerId = employerId,
                            payPeriodId = g.payPeriodId,
                            startDate = g.startDate,
                            endDate = g.endDate,
                            checkDate = g.checkDate,
                            frequency = g.frequency,
                            sequenceInYear = g.sequenceInYear,
                        ),
                    )

                    audit.record(
                        employerId = employerId,
                        entityType = "pay_period",
                        entityId = g.payPeriodId,
                        action = "CREATED",
                        effectiveFrom = g.checkDate,
                        before = null,
                        after = g,
                        ctx = HrAuditService.AuditContext(idempotencyKey = idempotencyKey),
                    )
                }

                created += 1
                createdIds += g.payPeriodId
            }

            val status = if (created > 0 && !dryRun) HttpStatus.CREATED else HttpStatus.OK
            ResponseEntity.status(status).body(
                mapOf(
                    "status" to if (dryRun) "DRY_RUN" else "OK",
                    "generatedCount" to generated.size,
                    "createdCount" to created,
                    "createdIds" to createdIds,
                ),
            )
        }
    }
}
