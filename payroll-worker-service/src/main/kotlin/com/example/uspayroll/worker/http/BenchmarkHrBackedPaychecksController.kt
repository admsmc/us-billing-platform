package com.example.uspayroll.worker.http

import com.example.uspayroll.hr.client.HrClient
import com.example.uspayroll.hr.client.HrClientProperties
import com.example.uspayroll.payroll.model.PaycheckResult
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.toLocalityCodeStrings
import com.example.uspayroll.worker.LocalityResolver
import com.example.uspayroll.worker.PayrollRunService
import com.example.uspayroll.worker.client.LaborStandardsClient
import com.example.uspayroll.worker.client.TaxClient
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.RequestEntity
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestTemplate
import org.springframework.web.client.exchange
import java.net.URI
import java.util.UUID

@ConfigurationProperties(prefix = "worker.benchmarks")
data class WorkerBenchmarksProperties(
    var enabled: Boolean = false,
    /** Shared secret for benchmark endpoints (simple dev guardrail). */
    var token: String = "",
    var headerName: String = "X-Benchmark-Token",
    /** Guardrail to avoid accidental huge in-process runs. */
    var maxEmployeeIdsPerRequest: Int = 2_000,
)

@Configuration
@EnableConfigurationProperties(WorkerBenchmarksProperties::class)
class WorkerBenchmarksConfig

@RestController
@ConditionalOnProperty(prefix = "worker.benchmarks", name = ["enabled"], havingValue = "true")
@RequestMapping("/benchmarks/employers/{employerId}")
class BenchmarkHrBackedPaychecksController(
    private val payrollRunService: PayrollRunService,
    private val localityResolver: LocalityResolver,
    private val hrClient: HrClient?,
    private val taxClient: TaxClient?,
    private val laborClient: LaborStandardsClient?,
    private val hrClientProperties: HrClientProperties,
    private val restTemplate: RestTemplate,
    private val props: WorkerBenchmarksProperties,
) {

    data class HrBackedPayPeriodRequest(
        val payPeriodId: String,
        /**
         * Optional explicit list of employee IDs.
         *
         * Prefer using employeeIdPrefix + range for large runs.
         */
        val employeeIds: List<String> = emptyList(),
        /**
         * When provided with employeeIdStartInclusive + employeeIdEndInclusive,
         * generates IDs like: <prefix><zero-padded number>.
         */
        val employeeIdPrefix: String? = null,
        val employeeIdStartInclusive: Int? = null,
        val employeeIdEndInclusive: Int? = null,
        val employeeIdPadWidth: Int = 6,
        /** Optional explicit run id. If omitted, a UUID is used. */
        val runId: String? = null,
        /**
         * Optional correctness mode.
         * - null/"off": no extra correctness work (fastest)
         * - "digest": compute aggregate invariants + a commutative digest over paycheck line items
         */
        val correctnessMode: String? = null,
    )

    data class CorrectnessSummary(
        val schemaVersion: Int,
        val digestXor: Long,
        val digestSum: Long,
        val paycheckCount: Int,
        val grossCentsTotal: Long,
        val netCentsTotal: Long,
        val earningsCentsTotal: Long,
        val employeeTaxesCentsTotal: Long,
        val employerTaxesCentsTotal: Long,
        val deductionsCentsTotal: Long,
        val employerContribCentsTotal: Long,
        /** Σ(gross - employeeTaxes - deductions - net) across paychecks; should be 0. */
        val netIdentityDeltaCentsTotal: Long,
        val earningsLineCount: Int,
        val employeeTaxLineCount: Int,
        val employerTaxLineCount: Int,
        val deductionLineCount: Int,
        val employerContribLineCount: Int,
    )

    data class HrBackedPayPeriodResponse(
        val employerId: String,
        val payPeriodId: String,
        val runId: String,
        val paychecks: Int,
        val totalGarnishmentWithheldCents: Long,
        val elapsedMillis: Long,
        val correctness: CorrectnessSummary? = null,
    )

    @PostMapping("/hr-backed-pay-period")
    fun runHrBackedPayPeriod(@PathVariable employerId: String, @RequestBody request: HrBackedPayPeriodRequest, servletRequest: HttpServletRequest): ResponseEntity<Any> {
        // Simple shared-secret guardrail.
        val tokenHeader = servletRequest.getHeader(props.headerName)
        if (props.token.isNotBlank() && props.token != tokenHeader) {
            return ResponseEntity.status(401).body(mapOf("error" to "unauthorized"))
        }

        val employeeIds = buildEmployeeIds(request)
        if (employeeIds.isEmpty()) {
            return ResponseEntity.badRequest().body(mapOf("error" to "employeeIds is required (either explicit list or prefix+range)"))
        }
        if (employeeIds.size > props.maxEmployeeIdsPerRequest) {
            return ResponseEntity.badRequest().body(
                mapOf(
                    "error" to "employeeIds size ${employeeIds.size} exceeds maxEmployeeIdsPerRequest=${props.maxEmployeeIdsPerRequest}",
                ),
            )
        }

        val runId = request.runId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()

        val start = System.nanoTime()
        val paychecks = payrollRunService.runHrBackedPayForPeriod(
            employerId = EmployerId(employerId),
            payPeriodId = request.payPeriodId,
            employeeIds = employeeIds,
            runId = runId,
        )
        val elapsedMillis = (System.nanoTime() - start) / 1_000_000

        // Heuristic: garnishment deductions use orderId as code (e.g. ORDER-BENCH-...).
        // This allows us to report a basic withheld total without re-querying HR.
        val withheldCents = paychecks
            .flatMap { it.deductions }
            .filter { it.code.value.startsWith("ORDER-") }
            .sumOf { it.amount.amount }

        val correctness = when (request.correctnessMode?.trim()?.lowercase()) {
            null, "", "off" -> null
            "digest" -> computeCorrectnessSummary(paychecks)
            else -> return ResponseEntity.badRequest().body(mapOf("error" to "unknown correctnessMode: ${request.correctnessMode}"))
        }

        return ResponseEntity.ok(
            HrBackedPayPeriodResponse(
                employerId = employerId,
                payPeriodId = request.payPeriodId,
                runId = runId,
                paychecks = paychecks.size,
                totalGarnishmentWithheldCents = withheldCents,
                elapsedMillis = elapsedMillis,
                correctness = correctness,
            ),
        )
    }

    private fun computeCorrectnessSummary(paychecks: List<PaycheckResult>): CorrectnessSummary {
        var digestXor = 0L
        var digestSum = 0L

        var grossCentsTotal = 0L
        var netCentsTotal = 0L
        var earningsCentsTotal = 0L
        var employeeTaxesCentsTotal = 0L
        var employerTaxesCentsTotal = 0L
        var deductionsCentsTotal = 0L
        var employerContribCentsTotal = 0L
        var netIdentityDeltaCentsTotal = 0L

        var earningsLineCount = 0
        var employeeTaxLineCount = 0
        var employerTaxLineCount = 0
        var deductionLineCount = 0
        var employerContribLineCount = 0

        fun mix64(x0: Long): Long {
            var x = x0
            x = (x xor (x ushr 33)) * -0xae502812aa7333L
            x = (x xor (x ushr 33)) * -0x3d4d51cb5a3b1b9L
            x = x xor (x ushr 33)
            return x
        }

        fun hashString64(s: String?): Long {
            // Keep this cheap. We intentionally do not do a cryptographic hash.
            val h = (s ?: "").hashCode().toLong()
            return mix64(h)
        }

        fun hashLong64(v: Long): Long = mix64(v)

        fun hashDoubleBits64(v: Double?): Long {
            val bits = if (v == null) 0L else java.lang.Double.doubleToLongBits(v)
            return mix64(bits)
        }

        fun combine(a: Long, b: Long): Long = mix64(a xor b)

        fun addRecord(h: Long) {
            digestXor = digestXor xor h
            digestSum += h
        }

        for (p in paychecks) {
            val header = run {
                // Do NOT include paycheckId/payRunId because the benchmark controller may vary runId.
                var h = hashString64(p.employerId.value)
                h = combine(h, hashString64(p.employeeId.value))
                h = combine(h, hashString64(p.period.id))
                h = combine(h, hashLong64(p.period.checkDate.toEpochDay()))
                h
            }

            val grossCents = p.gross.amount
            val netCents = p.net.amount
            grossCentsTotal += grossCents
            netCentsTotal += netCents

            var paycheckEmployeeTaxCents = 0L
            var paycheckDeductionCents = 0L

            // Top-level snapshot record
            addRecord(
                combine(
                    header,
                    combine(hashLong64(grossCents), hashLong64(netCents)),
                ),
            )

            // Earnings
            earningsLineCount += p.earnings.size
            for (e in p.earnings) {
                earningsCentsTotal += e.amount.amount

                val rec = run {
                    var h = combine(header, hashString64(e.code.value))
                    h = combine(h, hashString64(e.category.name))
                    h = combine(h, hashLong64(e.amount.amount))
                    h = combine(h, hashDoubleBits64(e.units))
                    h = combine(h, hashLong64(e.rate?.amount ?: 0L))
                    h
                }
                addRecord(rec)
            }

            // Employee taxes
            employeeTaxLineCount += p.employeeTaxes.size
            for (t in p.employeeTaxes) {
                employeeTaxesCentsTotal += t.amount.amount
                paycheckEmployeeTaxCents += t.amount.amount

                val rec = run {
                    var h = combine(header, hashString64(t.ruleId))
                    h = combine(h, hashString64(t.jurisdiction.type.name))
                    h = combine(h, hashString64(t.jurisdiction.code))
                    h = combine(h, hashLong64(t.basis.amount))
                    h = combine(h, hashDoubleBits64(t.rate?.value))
                    h = combine(h, hashLong64(t.amount.amount))
                    h
                }
                addRecord(rec)
            }

            // Employer taxes
            employerTaxLineCount += p.employerTaxes.size
            for (t in p.employerTaxes) {
                employerTaxesCentsTotal += t.amount.amount

                val rec = run {
                    var h = combine(header, hashString64(t.ruleId))
                    h = combine(h, hashString64("EMPLOYER"))
                    h = combine(h, hashString64(t.jurisdiction.type.name))
                    h = combine(h, hashString64(t.jurisdiction.code))
                    h = combine(h, hashLong64(t.basis.amount))
                    h = combine(h, hashDoubleBits64(t.rate?.value))
                    h = combine(h, hashLong64(t.amount.amount))
                    h
                }
                addRecord(rec)
            }

            // Deductions
            deductionLineCount += p.deductions.size
            for (d in p.deductions) {
                deductionsCentsTotal += d.amount.amount
                paycheckDeductionCents += d.amount.amount

                val rec = run {
                    var h = combine(header, hashString64(d.code.value))
                    h = combine(h, hashLong64(d.amount.amount))
                    h
                }
                addRecord(rec)
            }

            // Employer contributions
            employerContribLineCount += p.employerContributions.size
            for (c in p.employerContributions) {
                employerContribCentsTotal += c.amount.amount

                val rec = run {
                    var h = combine(header, hashString64(c.code.value))
                    h = combine(h, hashLong64(c.amount.amount))
                    h
                }
                addRecord(rec)
            }

            netIdentityDeltaCentsTotal += (grossCents - paycheckEmployeeTaxCents - paycheckDeductionCents - netCents)
        }

        return CorrectnessSummary(
            schemaVersion = 1,
            digestXor = digestXor,
            digestSum = digestSum,
            paycheckCount = paychecks.size,
            grossCentsTotal = grossCentsTotal,
            netCentsTotal = netCentsTotal,
            earningsCentsTotal = earningsCentsTotal,
            employeeTaxesCentsTotal = employeeTaxesCentsTotal,
            employerTaxesCentsTotal = employerTaxesCentsTotal,
            deductionsCentsTotal = deductionsCentsTotal,
            employerContribCentsTotal = employerContribCentsTotal,
            netIdentityDeltaCentsTotal = netIdentityDeltaCentsTotal,
            earningsLineCount = earningsLineCount,
            employeeTaxLineCount = employeeTaxLineCount,
            employerTaxLineCount = employerTaxLineCount,
            deductionLineCount = deductionLineCount,
            employerContribLineCount = employerContribLineCount,
        )
    }

    data class SeedVerificationResponse(
        val employerId: String,
        val payPeriodId: String,
        val employeeIdChecked: String,
        val hr: Map<String, Any?>,
        val tax: Map<String, Any?>,
        val labor: Map<String, Any?>,
        val localityCodes: List<String>,
        val ok: Boolean,
    )

    @GetMapping("/seed-verification")
    fun verifySeed(@PathVariable employerId: String, @RequestParam payPeriodId: String, @RequestParam employeeId: String, servletRequest: HttpServletRequest): ResponseEntity<Any> {
        val tokenHeader = servletRequest.getHeader(props.headerName)
        if (props.token.isNotBlank() && props.token != tokenHeader) {
            return ResponseEntity.status(401).body(mapOf("error" to "unauthorized"))
        }

        val hr = requireNotNull(hrClient) { "HrClient must be configured" }
        val tax = requireNotNull(taxClient) { "TaxClient must be configured" }
        val labor = requireNotNull(laborClient) { "LaborStandardsClient must be configured" }

        val employer = EmployerId(employerId)

        val period = hr.getPayPeriod(employer, payPeriodId)
        if (period == null) {
            return ResponseEntity.status(503).body(mapOf("error" to "HR pay period not found", "payPeriodId" to payPeriodId))
        }

        val snapshot = hr.getEmployeeSnapshot(employer, EmployeeId(employeeId), period.checkDate)
        if (snapshot == null) {
            return ResponseEntity.status(503).body(mapOf("error" to "HR employee snapshot not found", "employeeId" to employeeId))
        }

        val localityCodes = localityResolver
            .resolve(workState = snapshot.workState, workCity = snapshot.workCity)
            .toLocalityCodeStrings()

        val garnishments = hr.getGarnishmentOrders(employer, snapshot.employeeId, period.checkDate)

        val taxContext = tax.getTaxContext(
            employerId = employer,
            asOfDate = period.checkDate,
            residentState = snapshot.homeState,
            workState = snapshot.workState,
            localityCodes = localityCodes,
        )

        val laborContext = labor.getLaborStandards(
            employerId = employer,
            asOfDate = period.checkDate,
            workState = snapshot.workState,
            homeState = snapshot.homeState,
            localityCodes = localityCodes,
        )

        val ok = true
        return ResponseEntity.ok(
            SeedVerificationResponse(
                employerId = employerId,
                payPeriodId = payPeriodId,
                employeeIdChecked = employeeId,
                localityCodes = localityCodes,
                hr = mapOf(
                    "payPeriodFound" to true,
                    "snapshotFound" to true,
                    "garnishmentOrders" to garnishments.size,
                ),
                tax = mapOf(
                    "taxContextLoaded" to true,
                    "federalRules" to taxContext.federal.size,
                    "stateRules" to taxContext.state.size,
                    "localRules" to taxContext.local.size,
                    "employerSpecificRules" to taxContext.employerSpecific.size,
                ),
                labor = mapOf(
                    "laborStandardsLoaded" to (laborContext != null),
                ),
                ok = ok,
            ),
        )
    }

    data class RecommendedEmployeeRangeResponse(
        val employerId: String,
        val prefix: String?,
        val count: Long,
        val minEmployeeId: String?,
        val maxEmployeeId: String?,
        val inferredStartInclusive: Int?,
        val inferredEndInclusive: Int?,
        val padWidth: Int,
        val ok: Boolean,
    )

    data class CappedEmployeeRangeResponse(
        val employerId: String,
        val prefix: String?,
        val padWidth: Int,
        /** The raw range as reported by hr-service (min/max + inferred numeric range). */
        val full: RecommendedEmployeeRangeResponse?,
        /** A range capped to worker.benchmarks.maxEmployeeIdsPerRequest. */
        val safeStartInclusive: Int?,
        val safeEndInclusive: Int?,
        val safeCount: Int?,
        val maxEmployeeIdsPerRequest: Int,
        val capped: Boolean,
        val ok: Boolean,
    )

    /**
     * Convenience endpoint for k6: asks hr-service for min/max employee IDs so callers don't need to hardcode EMPLOYEE_ID_END.
     *
     * Also returns a "safe" capped range based on worker.benchmarks.maxEmployeeIdsPerRequest so load tests
     * don't accidentally exceed the worker's request-size guardrail.
     *
     * Requires hr-service `hr.benchmarks.enabled=true` and (optionally) the same benchmark token/header.
     */
    @GetMapping("/employee-id-range")
    fun getRecommendedEmployeeIdRange(
        @PathVariable employerId: String,
        @RequestParam(required = false) prefix: String?,
        @RequestParam(defaultValue = "6") padWidth: Int,
        /** Optional hint from callers (e.g. k6 EMPLOYEE_ID_START) to compute a safe end for that window. */
        @RequestParam(required = false) startInclusive: Int?,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<Any> {
        val tokenHeader = servletRequest.getHeader(props.headerName)
        if (props.token.isNotBlank() && props.token != tokenHeader) {
            return ResponseEntity.status(401).body(mapOf("error" to "unauthorized"))
        }

        val base = hrClientProperties.baseUrl
        val qPrefix = prefix?.takeIf { it.isNotBlank() }

        val url = if (qPrefix == null) {
            "$base/employers/$employerId/internal/benchmarks/employee-id-range?padWidth=$padWidth"
        } else {
            "$base/employers/$employerId/internal/benchmarks/employee-id-range?prefix=$qPrefix&padWidth=$padWidth"
        }

        val headers = HttpHeaders().apply {
            // Pass through the same benchmark token to HR if present.
            if (!tokenHeader.isNullOrBlank()) {
                set(props.headerName, tokenHeader)
            }
        }

        val response = restTemplate.exchange<RecommendedEmployeeRangeResponse>(
            RequestEntity.get(URI.create(url)).headers(headers).build(),
        )

        val full = response.body
        val fullStart = full?.inferredStartInclusive
        val fullEnd = full?.inferredEndInclusive

        val requestedStart = startInclusive ?: fullStart

        val safeStart = when {
            requestedStart == null -> null
            fullStart != null && requestedStart < fullStart -> fullStart
            fullEnd != null && requestedStart > fullEnd -> fullEnd
            else -> requestedStart
        }

        val safeEnd = when {
            safeStart == null -> null
            fullEnd == null -> null
            else -> {
                val cappedEnd = safeStart + props.maxEmployeeIdsPerRequest - 1
                minOf(fullEnd, cappedEnd)
            }
        }

        val safeCount = when {
            safeStart == null || safeEnd == null -> null
            safeEnd < safeStart -> 0
            else -> (safeEnd - safeStart + 1)
        }

        val capped = safeStart != null && safeEnd != null && fullEnd != null && safeEnd < fullEnd

        return ResponseEntity.status(response.statusCode).body(
            CappedEmployeeRangeResponse(
                employerId = employerId,
                prefix = qPrefix,
                padWidth = padWidth,
                full = full,
                safeStartInclusive = safeStart,
                safeEndInclusive = safeEnd,
                safeCount = safeCount,
                maxEmployeeIdsPerRequest = props.maxEmployeeIdsPerRequest,
                capped = capped,
                ok = (full?.ok == true),
            ),
        )
    }

    private fun buildEmployeeIds(request: HrBackedPayPeriodRequest): List<EmployeeId> {
        if (request.employeeIds.isNotEmpty()) {
            return request.employeeIds.map { EmployeeId(it) }
        }

        val prefix = request.employeeIdPrefix?.takeIf { it.isNotBlank() } ?: return emptyList()
        val start = request.employeeIdStartInclusive ?: return emptyList()
        val end = request.employeeIdEndInclusive ?: return emptyList()
        if (start <= 0 || end <= 0 || end < start) return emptyList()

        val width = request.employeeIdPadWidth.coerceIn(1, 12)
        val fmt = "%s%0${width}d"

        return (start..end).map { n ->
            EmployeeId(String.format(fmt, prefix, n))
        }
    }
}
