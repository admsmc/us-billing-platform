package com.example.uspayroll.timeingestion.http

import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.time.engine.TimeShaper
import com.example.uspayroll.time.model.OvertimeRuleSet
import com.example.uspayroll.time.model.TimeBuckets
import com.example.uspayroll.time.model.TimeEntry
import com.example.uspayroll.time.model.WorkweekDefinition
import com.example.uspayroll.timeingestion.repo.InMemoryTimeEntryRepository
import com.example.uspayroll.timeingestion.rules.TimeRuleSetResolver
import com.example.uspayroll.web.WebHeaders
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
    private val repo: InMemoryTimeEntryRepository,
    private val ruleSetResolver: TimeRuleSetResolver,
) {

    data class UpsertTimeEntryRequest(
        val date: LocalDate,
        val hours: Double,
        val worksiteKey: String? = null,
    )

    data class BulkUpsertItem(
        val entryId: String,
        val date: LocalDate,
        val hours: Double,
        val worksiteKey: String? = null,
    )

    data class BulkUpsertRequest(
        val entries: List<BulkUpsertItem>,
    )

    data class TimeBucketsDto(
        val regularHours: Double,
        val overtimeHours: Double,
        val doubleTimeHours: Double,
    ) {
        companion object {
            fun from(b: TimeBuckets): TimeBucketsDto = TimeBucketsDto(
                regularHours = b.regularHours,
                overtimeHours = b.overtimeHours,
                doubleTimeHours = b.doubleTimeHours,
            )
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

    @PutMapping("/time-entries/{entryId}")
    fun upsert(
        @PathVariable employerId: String,
        @PathVariable employeeId: String,
        @PathVariable entryId: String,
        @RequestHeader(name = WebHeaders.IDEMPOTENCY_KEY, required = false) idempotencyKey: String?,
        @RequestBody req: UpsertTimeEntryRequest,
    ): ResponseEntity<Any> {
        require(entryId.isNotBlank()) { "entryId must be non-blank" }
        require(req.hours >= 0.0) { "hours must be >= 0" }

        val existed = repo.upsert(
            employerId = EmployerId(employerId),
            employeeId = EmployeeId(employeeId),
            entry = InMemoryTimeEntryRepository.StoredTimeEntry(
                entryId = entryId,
                date = req.date,
                hours = req.hours,
                worksiteKey = req.worksiteKey,
            ),
        )

        val status = if (existed) HttpStatus.OK else HttpStatus.CREATED
        val body = mapOf(
            "status" to if (existed) "UPDATED" else "CREATED",
            "entryId" to entryId,
            "idempotencyKey" to idempotencyKey,
        )
        return ResponseEntity.status(status).body(body)
    }

    @PostMapping("/time-entries:bulk")
    fun bulkUpsert(
        @PathVariable employerId: String,
        @PathVariable employeeId: String,
        @RequestBody req: BulkUpsertRequest,
    ): ResponseEntity<Any> {
        require(req.entries.isNotEmpty()) { "entries must be non-empty" }
        req.entries.forEach { e ->
            require(e.entryId.isNotBlank()) { "entryId must be non-blank" }
            require(e.hours >= 0.0) { "hours must be >= 0" }
        }

        val existed = repo.upsertAll(
            employerId = EmployerId(employerId),
            employeeId = EmployeeId(employeeId),
            entries = req.entries.map { e ->
                InMemoryTimeEntryRepository.StoredTimeEntry(
                    entryId = e.entryId,
                    date = e.date,
                    hours = e.hours,
                    worksiteKey = e.worksiteKey,
                )
            },
        )

        return ResponseEntity.status(HttpStatus.OK).body(
            mapOf(
                "status" to "OK",
                "received" to req.entries.size,
                "updated" to existed,
                "created" to (req.entries.size - existed),
            ),
        )
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

        val employer = EmployerId(employerId)
        val employee = EmployeeId(employeeId)

        val stored = repo.findInRange(employer, employee, start, end)
        val entries = stored.map { s ->
            TimeEntry(
                date = s.date,
                hours = s.hours,
                worksiteKey = s.worksiteKey,
            )
        }

        val ruleSet = ruleSetResolver.resolve(employer, workState)

        val workweekDef = WorkweekDefinition(
            weekStartsOn = weekStartsOn?.trim()?.uppercase()?.let { DayOfWeek.valueOf(it) } ?: DayOfWeek.MONDAY,
        )

        val shaped = TimeShaper.shape(entries, ruleSet, workweekDef)

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
                totals = TimeBucketsDto.from(shaped.totals),
                byWorksite = shaped.byWorksite.mapValues { (_, b) -> TimeBucketsDto.from(b) },
            ),
        )
    }
}
