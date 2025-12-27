package com.example.usbilling.orchestrator.http

import com.example.usbilling.orchestrator.benchmarks.PayStatementRenderer
import com.example.usbilling.orchestrator.benchmarks.PaycheckCsvRenderer
import com.example.usbilling.orchestrator.persistence.PayRunPaycheckPayloadRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@ConfigurationProperties(prefix = "orchestrator.benchmarks")
data class OrchestratorBenchmarksProperties(
    var enabled: Boolean = false,
    /** Shared secret for benchmark endpoints (simple dev guardrail). */
    var token: String = "",
    var headerName: String = "X-Benchmark-Token",
    /** Guardrail to avoid accidental huge in-process renders. */
    var maxPaychecksPerRequest: Int = 10_000,
)

@Configuration
@EnableConfigurationProperties(OrchestratorBenchmarksProperties::class)
class OrchestratorBenchmarksConfig

@RestController
@ConditionalOnProperty(prefix = "orchestrator.benchmarks", name = ["enabled"], havingValue = "true")
@RequestMapping("/benchmarks/employers/{employerId}")
class BenchmarkPayRunArtifactsController(
    private val repo: PayRunPaycheckPayloadRepository,
    private val objectMapper: ObjectMapper,
    private val props: OrchestratorBenchmarksProperties,
) {

    data class RenderPayRunArtifactsRequest(
        /** If true, serialize each rendered statement to JSON bytes (simulates payload building). */
        val serializeJson: Boolean = true,
        /** If true, also generate a wide CSV (one paycheck per row, pay elements as columns). */
        val generateCsv: Boolean = false,
        /** Optional override for how many paychecks to render; capped by maxPaychecksPerRequest. */
        val limit: Int? = null,
    )

    data class RenderPayRunArtifactsResponse(
        val employerId: String,
        val payRunId: String,
        val paychecksRendered: Int,
        val elapsedMillisRender: Long,
        val serializedBytesTotal: Long,
        val csvBytesTotal: Long,
    )

    @PostMapping("/payruns/{payRunId}/render-pay-statements")
    fun renderPayStatements(
        @PathVariable employerId: String,
        @PathVariable payRunId: String,
        @RequestBody request: RenderPayRunArtifactsRequest,
        servletRequest: jakarta.servlet.http.HttpServletRequest,
    ): ResponseEntity<RenderPayRunArtifactsResponse> {
        val tokenHeader = servletRequest.getHeader(props.headerName)
        if (props.token.isNotBlank() && props.token != tokenHeader) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "BENCHMARK_UNAUTHORIZED")
        }

        val limit = (request.limit ?: props.maxPaychecksPerRequest)
            .coerceAtMost(props.maxPaychecksPerRequest)
            .coerceAtLeast(1)

        val rows = repo.listSucceededPaychecks(
            employerId = employerId,
            payRunId = payRunId,
            limit = limit,
        )

        val paychecks = rows.map { it.payload }

        val start = System.nanoTime()

        var bytesTotal = 0L
        if (request.serializeJson) {
            for (p in paychecks) {
                val statement = PayStatementRenderer.render(p)
                val bytes = objectMapper.writeValueAsBytes(statement)
                bytesTotal += bytes.size.toLong()
            }
        } else {
            // Still exercise the mapping cost.
            for (p in paychecks) {
                PayStatementRenderer.render(p)
            }
        }

        val csvBytes = if (request.generateCsv) {
            PaycheckCsvRenderer.renderWideCsv(paychecks, includeHeader = true).size.toLong()
        } else {
            0L
        }

        val elapsedMillis = (System.nanoTime() - start) / 1_000_000

        return ResponseEntity.ok(
            RenderPayRunArtifactsResponse(
                employerId = employerId,
                payRunId = payRunId,
                paychecksRendered = paychecks.size,
                elapsedMillisRender = elapsedMillis,
                serializedBytesTotal = bytesTotal,
                csvBytesTotal = csvBytes,
            ),
        )
    }

    @PostMapping("/payruns/{payRunId}/render-paychecks.csv")
    fun renderPaychecksCsv(
        @PathVariable employerId: String,
        @PathVariable payRunId: String,
        @RequestBody request: RenderPayRunArtifactsRequest,
        servletRequest: jakarta.servlet.http.HttpServletRequest,
    ): ResponseEntity<ByteArray> {
        val tokenHeader = servletRequest.getHeader(props.headerName)
        if (props.token.isNotBlank() && props.token != tokenHeader) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "BENCHMARK_UNAUTHORIZED")
        }

        val limit = (request.limit ?: props.maxPaychecksPerRequest)
            .coerceAtMost(props.maxPaychecksPerRequest)
            .coerceAtLeast(1)

        val rows = repo.listSucceededPaychecks(
            employerId = employerId,
            payRunId = payRunId,
            limit = limit,
        )

        val paychecks = rows.map { it.payload }
        val csvBytes = PaycheckCsvRenderer.renderWideCsv(paychecks, includeHeader = true)

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/csv"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=paychecks-$payRunId.csv")
            .body(csvBytes)
    }
}
