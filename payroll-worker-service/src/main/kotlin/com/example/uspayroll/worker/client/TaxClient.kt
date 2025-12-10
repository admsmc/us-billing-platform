package com.example.uspayroll.worker.client

import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.payroll.model.TaxContext
import com.example.uspayroll.tax.http.TaxContextDto
import com.example.uspayroll.tax.http.toDomain
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestTemplate
import org.springframework.web.client.getForObject
import java.time.LocalDate

/**
 * Client-side abstraction for talking to the Tax service.
 */
interface TaxClient {
    fun getTaxContext(
        employerId: EmployerId,
        asOfDate: LocalDate,
        localityCodes: List<String> = emptyList(),
    ): TaxContext
}

@ConfigurationProperties(prefix = "tax")
data class TaxClientProperties(
    var baseUrl: String = "http://localhost:8082",
)

@Configuration
@EnableConfigurationProperties(TaxClientProperties::class)
class TaxClientConfig {

    @Bean
    fun httpTaxClient(props: TaxClientProperties, restTemplate: RestTemplate): TaxClient =
        HttpTaxClient(props, restTemplate)
}

class HttpTaxClient(
    private val props: TaxClientProperties,
    private val restTemplate: RestTemplate,
) : TaxClient {

    override fun getTaxContext(
        employerId: EmployerId,
        asOfDate: LocalDate,
        localityCodes: List<String>,
    ): TaxContext {
        val localityParam = if (localityCodes.isEmpty()) "" else localityCodes.joinToString("&") { "locality=${it}" }
        val sep = if (localityParam.isEmpty()) "" else "&"
        val url = "${props.baseUrl}/employers/${employerId.value}/tax-context?asOf=$asOfDate$sep$localityParam"

        val dto = restTemplate.getForObject<TaxContextDto>(url)
            ?: error("Tax service returned null TaxContext for employer=${employerId.value} asOf=$asOfDate")

        return dto.toDomain()
    }
}
