package com.example.uspayroll.hr.http.benchmarks

import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@ConfigurationProperties(prefix = "hr.benchmarks")
data class HrBenchmarksProperties(
    var enabled: Boolean = false,
    var token: String = "",
    var headerName: String = "X-Benchmark-Token",
)

@Configuration
@EnableConfigurationProperties(HrBenchmarksProperties::class)
class HrBenchmarksConfig

@RestController
@ConditionalOnProperty(prefix = "hr.benchmarks", name = ["enabled"], havingValue = "true")
@RequestMapping("/employers/{employerId}/internal/benchmarks")
class BenchmarkEmployeeRangeController(
    private val jdbcTemplate: JdbcTemplate,
    private val props: HrBenchmarksProperties,
) {

    data class EmployeeIdRangeResponse(
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

    @GetMapping("/employee-id-range")
    fun getEmployeeIdRange(
        @PathVariable employerId: String,
        @RequestParam(required = false) prefix: String?,
        @RequestParam(defaultValue = "6") padWidth: Int,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        val tokenHeader = request.getHeader(props.headerName)
        if (props.token.isNotBlank() && props.token != tokenHeader) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "BENCHMARK_UNAUTHORIZED")
        }

        val likePrefix = prefix?.takeIf { it.isNotBlank() }

        val sql =
            """
            SELECT
              COUNT(*) AS cnt,
              MIN(employee_id) AS min_id,
              MAX(employee_id) AS max_id
            FROM employee
            WHERE employer_id = ?
              AND (? IS NULL OR employee_id LIKE ?)
            """.trimIndent()

        val row = jdbcTemplate.queryForMap(
            sql,
            employerId,
            likePrefix,
            likePrefix?.let { "$it%" },
        )

        val count = (row["cnt"] as? Number)?.toLong() ?: 0L
        val minId = row["min_id"] as? String
        val maxId = row["max_id"] as? String

        val width = padWidth.coerceIn(1, 12)
        fun parseSuffix(id: String?): Int? {
            if (id.isNullOrBlank() || likePrefix.isNullOrBlank()) return null
            if (!id.startsWith(likePrefix)) return null
            val suffix = id.removePrefix(likePrefix)
            if (suffix.isBlank() || suffix.any { !it.isDigit() }) return null
            return suffix.toIntOrNull()
        }

        val inferredStart = parseSuffix(minId)
        val inferredEnd = parseSuffix(maxId)

        return ResponseEntity.ok(
            EmployeeIdRangeResponse(
                employerId = employerId,
                prefix = likePrefix,
                count = count,
                minEmployeeId = minId,
                maxEmployeeId = maxId,
                inferredStartInclusive = inferredStart,
                inferredEndInclusive = inferredEnd,
                padWidth = width,
                ok = count > 0,
            ),
        )
    }
}
