package com.example.uspayroll.worker.client

import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestTemplate
import org.springframework.web.client.getForObject
import java.time.DayOfWeek
import java.time.LocalDate

interface TimeClient {
    fun getTimeSummary(
        employerId: EmployerId,
        employeeId: EmployeeId,
        start: LocalDate,
        end: LocalDate,
        workState: String? = null,
        weekStartsOn: DayOfWeek = DayOfWeek.MONDAY,
    ): TimeSummary

    data class TimeSummary(
        val regularHours: Double,
        val overtimeHours: Double,
        val doubleTimeHours: Double,
    )
}

@ConfigurationProperties(prefix = "time")
data class TimeClientProperties(
    var enabled: Boolean = false,
    var baseUrl: String = "http://localhost:8084",
)

@Configuration
@EnableConfigurationProperties(TimeClientProperties::class)
class TimeClientConfig {
    @Bean
    fun httpTimeClient(props: TimeClientProperties, restTemplate: RestTemplate): TimeClient = HttpTimeClient(props, restTemplate)
}

private data class TimeSummaryResponse(
    val totals: Totals,
) {
    data class Totals(
        val regularHours: Double,
        val overtimeHours: Double,
        val doubleTimeHours: Double,
    )
}

class HttpTimeClient(
    private val props: TimeClientProperties,
    private val restTemplate: RestTemplate,
) : TimeClient {

    override fun getTimeSummary(
        employerId: EmployerId,
        employeeId: EmployeeId,
        start: LocalDate,
        end: LocalDate,
        workState: String?,
        weekStartsOn: DayOfWeek,
    ): TimeClient.TimeSummary {
        if (!props.enabled) {
            return TimeClient.TimeSummary(regularHours = 0.0, overtimeHours = 0.0, doubleTimeHours = 0.0)
        }

        val params = ArrayList<String>(4)
        params.add("start=$start")
        params.add("end=$end")
        if (!workState.isNullOrBlank()) params.add("workState=$workState")
        params.add("weekStartsOn=${weekStartsOn.name}")

        val url = "${props.baseUrl}/employers/${employerId.value}/employees/${employeeId.value}/time-summary?" + params.joinToString("&")

        val dto = restTemplate.getForObject<TimeSummaryResponse>(url)
            ?: return TimeClient.TimeSummary(regularHours = 0.0, overtimeHours = 0.0, doubleTimeHours = 0.0)

        return TimeClient.TimeSummary(
            regularHours = dto.totals.regularHours,
            overtimeHours = dto.totals.overtimeHours,
            doubleTimeHours = dto.totals.doubleTimeHours,
        )
    }
}
