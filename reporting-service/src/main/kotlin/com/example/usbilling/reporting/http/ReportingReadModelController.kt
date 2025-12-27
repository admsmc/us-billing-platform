package com.example.usbilling.reporting.http

import com.example.usbilling.messaging.events.reporting.PaycheckLedgerEvent
import com.example.usbilling.reporting.persistence.PaycheckLedgerRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate

@RestController
@RequestMapping("/employers/{employerId}/reports")
class ReportingReadModelController(
    private val paycheckLedgerRepository: PaycheckLedgerRepository,
    private val objectMapper: ObjectMapper,
) {

    private fun requireValid(condition: Boolean, message: String) {
        if (!condition) throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)
    }

    data class LedgerEntrySummaryDto(
        val employerId: String,
        val paycheckId: String,
        val employeeId: String,
        val payRunId: String,
        val payRunType: String,
        val runSequence: Int,
        val payPeriodId: String,
        val checkDate: LocalDate,
        val action: String,
        val currency: String,
        val grossCents: Long,
        val netCents: Long,
        val eventId: String,
        val occurredAt: java.time.Instant,
        val payload: PaycheckLedgerEvent? = null,
    )

    data class LedgerRangeResponse(
        val employerId: String,
        val start: LocalDate,
        val end: LocalDate,
        val count: Int,
        val entries: List<LedgerEntrySummaryDto>,
    )

    @GetMapping("/paycheck-ledger")
    fun listPaycheckLedger(
        @PathVariable employerId: String,
        @RequestParam start: LocalDate,
        @RequestParam end: LocalDate,
        @RequestParam(name = "limit", defaultValue = "1000") limit: Int,
        @RequestParam(name = "includePayload", defaultValue = "false") includePayload: Boolean,
    ): ResponseEntity<LedgerRangeResponse> {
        requireValid(limit >= 1, "limit must be >= 1")
        requireValid(limit <= 10_000, "limit must be <= 10000")
        requireValid(!end.isBefore(start), "end must be >= start")

        val rows = paycheckLedgerRepository.listLedgerSummaryByEmployerAndCheckDateRange(
            employerId = employerId,
            startInclusive = start,
            endInclusive = end,
            limit = limit,
        )

        val payloadByPaycheckId =
            if (includePayload) {
                paycheckLedgerRepository.listLedgerPayloadsByEmployerAndCheckDateRange(
                    employerId = employerId,
                    startInclusive = start,
                    endInclusive = end,
                    limit = limit,
                ).associate { it.paycheckId to objectMapper.readValue(it.payloadJson, PaycheckLedgerEvent::class.java) }
            } else {
                emptyMap()
            }

        val entries = rows.map {
            LedgerEntrySummaryDto(
                employerId = it.employerId,
                paycheckId = it.paycheckId,
                employeeId = it.employeeId,
                payRunId = it.payRunId,
                payRunType = it.payRunType,
                runSequence = it.runSequence,
                payPeriodId = it.payPeriodId,
                checkDate = it.checkDate,
                action = it.action,
                currency = it.currency,
                grossCents = it.grossCents,
                netCents = it.netCents,
                eventId = it.eventId,
                occurredAt = it.occurredAt,
                payload = payloadByPaycheckId[it.paycheckId],
            )
        }

        return ResponseEntity.ok(
            LedgerRangeResponse(
                employerId = employerId,
                start = start,
                end = end,
                count = entries.size,
                entries = entries,
            ),
        )
    }

    data class NetTotalsByEmployeeRowDto(
        val employerId: String,
        val payRunId: String,
        val employeeId: String,
        val paychecks: Int,
        val grossCentsTotal: Long,
        val netCentsTotal: Long,
    )

    data class PayRunNetTotalsResponse(
        val employerId: String,
        val payRunId: String,
        val countEmployees: Int,
        val totals: List<NetTotalsByEmployeeRowDto>,
    )

    @GetMapping("/payruns/{payRunId}/net-totals")
    fun netTotalsByEmployeeForPayRun(
        @PathVariable employerId: String,
        @PathVariable payRunId: String,
        @RequestParam(name = "limit", defaultValue = "10000") limit: Int,
    ): ResponseEntity<PayRunNetTotalsResponse> {
        requireValid(limit >= 1, "limit must be >= 1")
        requireValid(limit <= 50_000, "limit must be <= 50000")

        val rows = paycheckLedgerRepository.listNetTotalsByEmployerAndPayRun(
            employerId = employerId,
            payRunId = payRunId,
            limit = limit,
        )

        val out = rows.map {
            NetTotalsByEmployeeRowDto(
                employerId = it.employerId,
                payRunId = it.payRunId,
                employeeId = it.employeeId,
                paychecks = it.paychecks,
                grossCentsTotal = it.grossCentsTotal,
                netCentsTotal = it.netCentsTotal,
            )
        }

        return ResponseEntity.ok(
            PayRunNetTotalsResponse(
                employerId = employerId,
                payRunId = payRunId,
                countEmployees = out.size,
                totals = out,
            ),
        )
    }

    data class PaycheckLedgerEntryResponse(
        val employerId: String,
        val paycheckId: String,
        val entry: LedgerEntrySummaryDto,
    )

    @GetMapping("/paycheck-ledger/{paycheckId}")
    fun getPaycheckLedgerEntry(
        @PathVariable employerId: String,
        @PathVariable paycheckId: String,
        @RequestParam(name = "includePayload", defaultValue = "false") includePayload: Boolean,
    ): ResponseEntity<PaycheckLedgerEntryResponse> {
        val summary = paycheckLedgerRepository.findLedgerSummaryByEmployerAndPaycheckId(employerId, paycheckId)
            ?: return ResponseEntity.notFound().build()

        val payload =
            if (includePayload) {
                val row = paycheckLedgerRepository.findLedgerPayloadByEmployerAndPaycheckId(employerId, paycheckId)
                    ?: return ResponseEntity.notFound().build()
                objectMapper.readValue(row.payloadJson, PaycheckLedgerEvent::class.java)
            } else {
                null
            }

        val dto = LedgerEntrySummaryDto(
            employerId = summary.employerId,
            paycheckId = summary.paycheckId,
            employeeId = summary.employeeId,
            payRunId = summary.payRunId,
            payRunType = summary.payRunType,
            runSequence = summary.runSequence,
            payPeriodId = summary.payPeriodId,
            checkDate = summary.checkDate,
            action = summary.action,
            currency = summary.currency,
            grossCents = summary.grossCents,
            netCents = summary.netCents,
            eventId = summary.eventId,
            occurredAt = summary.occurredAt,
            payload = payload,
        )

        return ResponseEntity.ok(
            PaycheckLedgerEntryResponse(
                employerId = employerId,
                paycheckId = paycheckId,
                entry = dto,
            ),
        )
    }
}
