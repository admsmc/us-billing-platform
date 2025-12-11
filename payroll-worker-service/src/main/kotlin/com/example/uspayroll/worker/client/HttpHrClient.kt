package com.example.uspayroll.worker.client

import com.example.uspayroll.payroll.model.EmployeeSnapshot
import com.example.uspayroll.payroll.model.PayPeriod
import com.example.uspayroll.payroll.model.garnishment.GarnishmentOrder
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.web.client.RestTemplate
import org.springframework.web.client.getForObject
import java.time.LocalDate

@ConfigurationProperties(prefix = "hr")
data class HrClientProperties(
    var baseUrl: String = "http://localhost:8081",
)

@Configuration
@EnableConfigurationProperties(HrClientProperties::class)
class HrClientConfig {

    /**
     * Shared RestTemplate for all HTTP-based clients in worker-service.
     */
    @Bean
    fun restTemplate(messageConverter: MappingJackson2HttpMessageConverter): RestTemplate =
        RestTemplate(listOf(messageConverter))

    @Bean
    fun httpHrClient(props: HrClientProperties, restTemplate: RestTemplate): HrClient =
        HttpHrClient(props, restTemplate)
}

class HttpHrClient(
    private val props: HrClientProperties,
    private val restTemplate: RestTemplate,
) : HrClient {

    override fun getEmployeeSnapshot(
        employerId: EmployerId,
        employeeId: EmployeeId,
        asOfDate: LocalDate,
    ): EmployeeSnapshot? {
        val url = "${props.baseUrl}/employers/${employerId.value}/employees/${employeeId.value}/snapshot?asOf=$asOfDate"
        return restTemplate.getForObject<EmployeeSnapshot>(url)
    }

    override fun getPayPeriod(
        employerId: EmployerId,
        payPeriodId: String,
    ): PayPeriod? {
        val url = "${props.baseUrl}/employers/${employerId.value}/pay-periods/$payPeriodId"
        return restTemplate.getForObject<PayPeriod>(url)
    }

    override fun findPayPeriodByCheckDate(
        employerId: EmployerId,
        checkDate: LocalDate,
    ): PayPeriod? {
        val url = "${props.baseUrl}/employers/${employerId.value}/pay-periods/by-check-date?checkDate=$checkDate"
        return restTemplate.getForObject<PayPeriod>(url)
    }

    override fun getGarnishmentOrders(
        employerId: EmployerId,
        employeeId: EmployeeId,
        asOfDate: LocalDate,
    ): List<GarnishmentOrder> {
        val url = "${props.baseUrl}/employers/${employerId.value}/employees/${employeeId.value}/garnishments?asOf=$asOfDate"
        val dtoArray = restTemplate.getForObject<Array<GarnishmentOrderDto>>(url) ?: return emptyList()
        return dtoArray.toList().map { it.toDomain() }
    }

    override fun recordGarnishmentWithholding(
        employerId: EmployerId,
        employeeId: EmployeeId,
        request: GarnishmentWithholdingRequest,
    ) {
        val url = "${props.baseUrl}/employers/${employerId.value}/employees/${employeeId.value}/garnishments/withholdings"
        restTemplate.postForLocation(url, request)
    }
}
