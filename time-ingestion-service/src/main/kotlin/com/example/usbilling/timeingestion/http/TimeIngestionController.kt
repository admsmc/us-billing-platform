package com.example.usbilling.timeingestion.http

import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import com.example.usbilling.time.engine.TimeShaper
import com.example.usbilling.time.model.OvertimeRuleSet
import com.example.usbilling.time.model.TimeBuckets
import com.example.usbilling.time.model.TimeEntry
import com.example.usbilling.time.model.TipAllocationRuleSet
import com.example.usbilling.time.model.WorkweekDefinition
import com.example.usbilling.timeingestion.repo.TimeEntryRepository
import com.example.usbilling.timeingestion.rules.TimeRuleSetResolver
import com.example.usbilling.timeingestion.rules.TipAllocationRuleSetResolver
import com.example.usbilling.web.Idempotency
import com.example.usbilling.web.WebHeaders
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.DayOfWeek
import java.time.LocalDate

@RestController
@RequestMapping("/employers/{employerId}/employees/{employeeId}")
class TimeIngestionController(
    private val repo: TimeEntryRepository,
    private val ruleSetResolver: TimeRuleSetResolver,
    private val tipRuleSetResolver: TipAllocationRuleSetResolver,
) {

    data class UpsertTimeEntryRequest(
        val date: LocalDate,
        val hours: Double,
        /** Tips received as cash (cents). */
        val cashTipsCents: Long? = null,
        /** Tips received via charged/credit card (cents). */
        val chargedTipsCents: Long? = null,
        /** Tips allocated to the employee via pooling (cents). */
        val allocatedTipsCents: Long? = null,
        /** Additional earnings (cents). */
        val commissionCents: Long? = null,
        val bonusCents: Long? = null,
        val reimbursementNonTaxableCents: Long? = null,
        val worksiteKey: String? = null,
    )

    data class BulkUpsertItem(
        val entryId: String,
        val date: LocalDate,
        val hours: Double,
        val cashTipsCents: Long? = null,
        val chargedTipsCents: Long? = null,
        val allocatedTipsCents: Long? = null,
        val commissionCents: Long? = null,
        val bonusCents: Long? = null,
        val reimbursementNonTaxableCents: Long? = null,
        val worksiteKey: String? = null,
    )

    data class BulkUpsertRequest(
        val entries: List<BulkUpsertItem>,
    )

    data class TimeBucketsDto(
        val regularHours: Double,
        val overtimeHours: Double,
        val doubleTimeHours: Double,
        val cashTipsCents: Long,
        val chargedTipsCents: Long,
        val allocatedTipsCents: Long,
        val commissionCents: Long,
        val bonusCents: Long,
        val reimbursementNonTaxableCents: Long,
    ) {
        companion object {
            fun from(b: TimeBuckets, tips: TipTotals, earnings: OtherEarningsTotals): TimeBucketsDto = TimeBucketsDto(
                regularHours = b.regularHours,
                overtimeHours = b.overtimeHours,
                doubleTimeHours = b.doubleTimeHours,
                cashTipsCents = tips.cashTipsCents,
                chargedTipsCents = tips.chargedTipsCents,
                allocatedTipsCents = tips.allocatedTipsCents,
                commissionCents = earnings.commissionCents,
                bonusCents = earnings.bonusCents,
                reimbursementNonTaxableCents = earnings.reimbursementNonTaxableCents,
            )
        }
    }

    data class TipTotals(
        val cashTipsCents: Long,
        val chargedTipsCents: Long,
        val allocatedTipsCents: Long,
    ) {
        companion object {
            val Zero = TipTotals(cashTipsCents = 0L, chargedTipsCents = 0L, allocatedTipsCents = 0L)
        }
    }

    data class OtherEarningsTotals(
        val commissionCents: Long,
        val bonusCents: Long,
        val reimbursementNonTaxableCents: Long,
    ) {
        companion object {
            val Zero = OtherEarningsTotals(commissionCents = 0L, bonusCents = 0L, reimbursementNonTaxableCents = 0L)
        }
    }

    data class TimeSummaryResponse(
        val employerId: String,
        val employeeId: String,
        val start: LocalDate,
        val end: LocalDate,
        val ruleSet: String,
        val totals: TimeBucketsDto,
        val byWorksite: Map<String, TimeBucketsDto>,
    )

    data class UpsertTimeEntryResponse(
        val status: String,
        val entryId: String,
        val idempotencyKey: String?,
    )

    data class BulkUpsertResponse(
        val status: String,
        val received: Int,
        val updated: Int,
        val created: Int,
    )

    @PutMapping("/time-entries/{entryId}")
    fun upsert(
        @PathVariable employerId: String,
        @PathVariable employeeId: String,
        @PathVariable entryId: String,
        @RequestHeader(name = WebHeaders.IDEMPOTENCY_KEY, required = false) idempotencyKey: String?,
        @RequestBody req: UpsertTimeEntryRequest,
    ): ResponseEntity<UpsertTimeEntryResponse> {
        require(entryId.isNotBlank()) { "entryId must be non-blank" }
        require(req.hours >= 0.0) { "hours must be >= 0" }

        val cashTipsCents = req.cashTipsCents ?: 0L
        val chargedTipsCents = req.chargedTipsCents ?: 0L
        val allocatedTipsCents = req.allocatedTipsCents ?: 0L
        val commissionCents = req.commissionCents ?: 0L
        val bonusCents = req.bonusCents ?: 0L
        val reimbursementNonTaxableCents = req.reimbursementNonTaxableCents ?: 0L
        require(cashTipsCents >= 0L) { "cashTipsCents must be >= 0" }
        require(chargedTipsCents >= 0L) { "chargedTipsCents must be >= 0" }
        require(allocatedTipsCents >= 0L) { "allocatedTipsCents must be >= 0" }
        require(commissionCents >= 0L) { "commissionCents must be >= 0" }
        require(bonusCents >= 0L) { "bonusCents must be >= 0" }
        require(reimbursementNonTaxableCents >= 0L) { "reimbursementNonTaxableCents must be >= 0" }

        val existed = repo.upsert(
            employerId = UtilityId(employerId),
            employeeId = CustomerId(employeeId),
            entry = TimeEntryRepository.StoredTimeEntry(
                entryId = entryId,
                date = req.date,
                hours = req.hours,
                cashTipsCents = cashTipsCents,
                chargedTipsCents = chargedTipsCents,
                allocatedTipsCents = allocatedTipsCents,
                commissionCents = commissionCents,
                bonusCents = bonusCents,
                reimbursementNonTaxableCents = reimbursementNonTaxableCents,
                worksiteKey = req.worksiteKey,
            ),
        )

        val normalizedIdempotencyKey = Idempotency.normalize(idempotencyKey)
        val status = if (existed) HttpStatus.OK else HttpStatus.CREATED
        val body = UpsertTimeEntryResponse(
            status = if (existed) "UPDATED" else "CREATED",
            entryId = entryId,
            idempotencyKey = normalizedIdempotencyKey,
        )
        return ResponseEntity.status(status).body(body)
    }

    @PostMapping("/time-entries:bulk")
    fun bulkUpsert(
        @PathVariable employerId: String,
        @PathVariable employeeId: String,
        @RequestBody req: BulkUpsertRequest,
    ): ResponseEntity<BulkUpsertResponse> {
        require(req.entries.isNotEmpty()) { "entries must be non-empty" }
        req.entries.forEach { e ->
            require(e.entryId.isNotBlank()) { "entryId must be non-blank" }
            require(e.hours >= 0.0) { "hours must be >= 0" }
            require((e.cashTipsCents ?: 0L) >= 0L) { "cashTipsCents must be >= 0" }
            require((e.chargedTipsCents ?: 0L) >= 0L) { "chargedTipsCents must be >= 0" }
            require((e.allocatedTipsCents ?: 0L) >= 0L) { "allocatedTipsCents must be >= 0" }
            require((e.commissionCents ?: 0L) >= 0L) { "commissionCents must be >= 0" }
            require((e.bonusCents ?: 0L) >= 0L) { "bonusCents must be >= 0" }
            require((e.reimbursementNonTaxableCents ?: 0L) >= 0L) { "reimbursementNonTaxableCents must be >= 0" }
        }

        val existed = repo.upsertAll(
            employerId = UtilityId(employerId),
            employeeId = CustomerId(employeeId),
            entries = req.entries.map { e ->
                TimeEntryRepository.StoredTimeEntry(
                    entryId = e.entryId,
                    date = e.date,
                    hours = e.hours,
                    cashTipsCents = e.cashTipsCents ?: 0L,
                    chargedTipsCents = e.chargedTipsCents ?: 0L,
                    allocatedTipsCents = e.allocatedTipsCents ?: 0L,
                    commissionCents = e.commissionCents ?: 0L,
                    bonusCents = e.bonusCents ?: 0L,
                    reimbursementNonTaxableCents = e.reimbursementNonTaxableCents ?: 0L,
                    worksiteKey = e.worksiteKey,
                )
            },
        )

        val response = BulkUpsertResponse(
            status = "OK",
            received = req.entries.size,
            updated = existed,
            created = req.entries.size - existed,
        )

        return ResponseEntity.status(HttpStatus.OK).body(response)
    }

    @GetMapping("/time-summary")
    fun getTimeSummary(
        @PathVariable employerId: String,
        @PathVariable employeeId: String,
        @RequestParam("start") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) start: LocalDate,
        @RequestParam("end") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) end: LocalDate,
        @RequestParam("workState", required = false) workState: String?,
        @RequestParam("weekStartsOn", required = false) weekStartsOn: String?,
    ): ResponseEntity<TimeSummaryResponse> {
        require(!end.isBefore(start)) { "end must be >= start" }

        val employer = UtilityId(employerId)
        val employee = CustomerId(employeeId)

        val stored = repo.findInRange(employer, employee, start, end)
        val entries = stored.map { s ->
            TimeEntry(
                date = s.date,
                hours = s.hours,
                cashTipsCents = s.cashTipsCents,
                chargedTipsCents = s.chargedTipsCents,
                allocatedTipsCents = s.allocatedTipsCents,
                commissionCents = s.commissionCents,
                bonusCents = s.bonusCents,
                reimbursementNonTaxableCents = s.reimbursementNonTaxableCents,
                worksiteKey = s.worksiteKey,
            )
        }

        val ruleSet = ruleSetResolver.resolve(employer, workState)

        val workweekDef = WorkweekDefinition(
            weekStartsOn = weekStartsOn?.trim()?.uppercase()?.let { DayOfWeek.valueOf(it) } ?: DayOfWeek.MONDAY,
        )

        val shaped = TimeShaper.shape(entries, ruleSet, workweekDef)

        fun worksiteKeyOrDefault(k: String?): String = k ?: "__default__"

        // Raw tips for *this* employee.
        val rawTipsByKey: Map<String, TipTotals> = entries
            .groupBy { worksiteKeyOrDefault(it.worksiteKey) }
            .mapValues { (_, es) ->
                TipTotals(
                    cashTipsCents = es.sumOf { it.cashTipsCents },
                    chargedTipsCents = es.sumOf { it.chargedTipsCents },
                    allocatedTipsCents = es.sumOf { it.allocatedTipsCents }, // manual allocated tips (if any)
                )
            }

        // Computed tip pooling allocation (worksite-level) based on employer/state rules.
        val tipRules = tipRuleSetResolver.resolve(employer, workState)

        val computedAllocatedByKey: Map<String, Long> = when (tipRules) {
            is TipAllocationRuleSet.None -> emptyMap()
            is TipAllocationRuleSet.SimplePool -> computeAllocatedTipsByWorksite(
                employer = employer,
                employee = employee,
                start = start,
                end = end,
                poolPercent = tipRules.poolPercentOfCharged,
                worksiteKeyOrDefault = ::worksiteKeyOrDefault,
            )
        }

        val tipsByKey: Map<String, TipTotals> = (rawTipsByKey.keys + computedAllocatedByKey.keys)
            .associateWith { key ->
                val raw = rawTipsByKey[key] ?: TipTotals.Zero
                val computed = computedAllocatedByKey[key] ?: 0L
                raw.copy(allocatedTipsCents = raw.allocatedTipsCents + computed)
            }

        val totalTips = TipTotals(
            cashTipsCents = tipsByKey.values.sumOf { it.cashTipsCents },
            chargedTipsCents = tipsByKey.values.sumOf { it.chargedTipsCents },
            allocatedTipsCents = tipsByKey.values.sumOf { it.allocatedTipsCents },
        )

        val rawEarningsByKey: Map<String, OtherEarningsTotals> = entries
            .groupBy { worksiteKeyOrDefault(it.worksiteKey) }
            .mapValues { (_, es) ->
                OtherEarningsTotals(
                    commissionCents = es.sumOf { it.commissionCents },
                    bonusCents = es.sumOf { it.bonusCents },
                    reimbursementNonTaxableCents = es.sumOf { it.reimbursementNonTaxableCents },
                )
            }

        val totalOtherEarnings = OtherEarningsTotals(
            commissionCents = rawEarningsByKey.values.sumOf { it.commissionCents },
            bonusCents = rawEarningsByKey.values.sumOf { it.bonusCents },
            reimbursementNonTaxableCents = rawEarningsByKey.values.sumOf { it.reimbursementNonTaxableCents },
        )

        val hoursByWorksite = shaped.byWorksite

        // Union keys so tips-only/earnings-only/hours-only worksites still show up.
        val byWorksiteKeys = (hoursByWorksite.keys + tipsByKey.keys + rawEarningsByKey.keys)
            .filter { it != "__default__" }
            .toSortedSet()

        val byWorksiteDto: Map<String, TimeBucketsDto> = byWorksiteKeys.associateWith { key ->
            val hours = hoursByWorksite[key] ?: TimeBuckets(0.0, 0.0, 0.0)
            val tips = tipsByKey[key] ?: TipTotals.Zero
            val earnings = rawEarningsByKey[key] ?: OtherEarningsTotals.Zero
            TimeBucketsDto.from(hours, tips, earnings)
        }

        val ruleSetLabel = when (ruleSet) {
            is OvertimeRuleSet.None -> "NONE"
            is OvertimeRuleSet.Simple -> ruleSet.id
        }

        return ResponseEntity.ok(
            TimeSummaryResponse(
                employerId = employerId,
                employeeId = employeeId,
                start = start,
                end = end,
                ruleSet = ruleSetLabel,
                totals = TimeBucketsDto.from(shaped.totals, totalTips, totalOtherEarnings),
                byWorksite = byWorksiteDto,
            ),
        )
    }

    private fun computeAllocatedTipsByWorksite(
        employer: UtilityId,
        employee: CustomerId,
        start: LocalDate,
        end: LocalDate,
        poolPercent: Double,
        worksiteKeyOrDefault: (String?) -> String,
    ): Map<String, Long> {
        if (poolPercent <= 0.0) return emptyMap()

        val all = repo.findAllInRange(employerId = employer, start = start, end = end)

        data class Acc(
            var chargedTipsTotalCents: Long = 0L,
            var eligibleHoursTotal: Double = 0.0,
            val eligibleHoursByEmployee: MutableMap<CustomerId, Double> = HashMap(),
        )

        fun isTipEligible(e: TimeEntryRepository.StoredTimeEntry): Boolean {
            // Approximation: employees with any recorded tips participate in pool allocation.
            val hasTips = (e.cashTipsCents + e.chargedTipsCents) > 0L
            return hasTips && e.hours > 0.0
        }

        val accByWorksite = HashMap<String, Acc>()

        for (row in all) {
            val e = row.entry
            val key = worksiteKeyOrDefault(e.worksiteKey)
            val acc = accByWorksite.getOrPut(key) { Acc() }

            acc.chargedTipsTotalCents += e.chargedTipsCents

            if (isTipEligible(e)) {
                acc.eligibleHoursTotal += e.hours
                acc.eligibleHoursByEmployee[row.employeeId] = (acc.eligibleHoursByEmployee[row.employeeId] ?: 0.0) + e.hours
            }
        }

        val out = HashMap<String, Long>()

        for ((worksite, acc) in accByWorksite) {
            val denom = acc.eligibleHoursTotal
            val poolCents = kotlin.math.floor(acc.chargedTipsTotalCents.toDouble() * poolPercent).toLong()
            val empHours = acc.eligibleHoursByEmployee[employee] ?: 0.0

            val alloc =
                if (denom > 0.0 && poolCents > 0L && empHours > 0.0) {
                    kotlin.math.floor(poolCents.toDouble() * (empHours / denom)).toLong()
                } else {
                    0L
                }

            if (alloc > 0L) {
                out[worksite] = alloc
            }
        }

        return out
    }
}
