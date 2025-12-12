package com.example.uspayroll.orchestrator.client

import com.example.uspayroll.labor.http.LaborStandardsContextDto
import com.example.uspayroll.labor.http.toDomain
import com.example.uspayroll.payroll.model.LaborStandardsContext
import com.example.uspayroll.shared.EmployerId
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestTemplate
import org.springframework.web.client.getForObject
import java.time.LocalDate

interface LaborStandardsClient {
    fun getLaborStandards(
        employerId: EmployerId,
        asOfDate: LocalDate,
        workState: String?,
        homeState: String?,
        localityCodes: List<String> = emptyList(),
    ): LaborStandardsContext?
}

@ConfigurationProperties(prefix = "labor")
data class LaborClientProperties(
    var baseUrl: String = "http://localhost:8083",
)

@Configuration
@EnableConfigurationProperties(LaborClientProperties::class)
class LaborClientConfig {

    @Bean
    fun httpLaborStandardsClient(props: LaborClientProperties, hrRestTemplate: RestTemplate): LaborStandardsClient =
        HttpLaborStandardsClient(props, hrRestTemplate)
}

class HttpLaborStandardsClient(
    private val props: LaborClientProperties,
    private val restTemplate: RestTemplate,
) : LaborStandardsClient {

    override fun getLaborStandards(
        employerId: EmployerId,
        asOfDate: LocalDate,
        workState: String?,
        homeState: String?,
        localityCodes: List<String>,
    ): LaborStandardsContext? {
        if (workState == null) return null

        val base = "${props.baseUrl}/employers/${employerId.value}/labor-standards"
        val baseQuery = if (homeState != null) {
            "$base?asOf=$asOfDate&state=$workState&homeState=$homeState"
        } else {
            "$base?asOf=$asOfDate&state=$workState"
        }

        val localityQuery = if (localityCodes.isNotEmpty()) {
            localityCodes.joinToString(separator = "") { code -> "&locality=$code" }
        } else {
            ""
        }

        val url = baseQuery + localityQuery
        val dto = restTemplate.getForObject<LaborStandardsContextDto>(url) ?: return null
        return dto.toDomain()
    }
}
