package com.example.uspayroll.worker.client

import com.example.uspayroll.payroll.model.TaxContext
import com.example.uspayroll.shared.EmployerId
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
    fun getTaxContext(employerId: EmployerId, asOfDate: LocalDate, residentState: String? = null, workState: String? = null, localityCodes: List<String> = emptyList()): TaxContext
}

@ConfigurationProperties(prefix = "tax")
data class TaxClientProperties(
    var baseUrl: String = "http://localhost:8082",
)

@Configuration
@EnableConfigurationProperties(TaxClientProperties::class)
class TaxClientConfig {

    @Bean
    fun httpTaxClient(props: TaxClientProperties, restTemplate: RestTemplate): TaxClient = HttpTaxClient(props, restTemplate)
}

class HttpTaxClient(
    private val props: TaxClientProperties,
    private val restTemplate: RestTemplate,
) : TaxClient {

    override fun getTaxContext(employerId: EmployerId, asOfDate: LocalDate, residentState: String?, workState: String?, localityCodes: List<String>): TaxContext {
        val params = ArrayList<String>(3 + localityCodes.size)
        params.add("asOf=$asOfDate")
        if (!residentState.isNullOrBlank()) params.add("residentState=$residentState")
        if (!workState.isNullOrBlank()) params.add("workState=$workState")
        localityCodes.forEach { code -> params.add("locality=$code") }

        val url = "${props.baseUrl}/employers/${employerId.value}/tax-context?" + params.joinToString("&")

        val dto = restTemplate.getForObject<TaxContextDto>(url)
            ?: error("Tax service returned null TaxContext for employer=${employerId.value} asOf=$asOfDate")

        return dto.toDomain()
    }
}
