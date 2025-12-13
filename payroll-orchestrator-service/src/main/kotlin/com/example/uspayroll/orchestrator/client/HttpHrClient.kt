package com.example.uspayroll.orchestrator.client

import com.example.uspayroll.hr.client.HrClient
import com.example.uspayroll.hr.client.HrClientProperties
import com.example.uspayroll.hr.http.GarnishmentOrderDto
import com.example.uspayroll.hr.http.GarnishmentWithholdingRequest
import com.example.uspayroll.hr.http.toDomain
import com.example.uspayroll.payroll.model.EmployeeSnapshot
import com.example.uspayroll.payroll.model.PayPeriod
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.web.RestTemplateMdcPropagationInterceptor
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestTemplate
import org.springframework.web.client.getForObject
import java.time.LocalDate

@Configuration
@EnableConfigurationProperties(HrClientProperties::class)
class HrClientConfig {

    @Bean
    fun hrRestTemplate(props: HrClientProperties): RestTemplate {
        val requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(props.connectTimeout)
            setReadTimeout(props.readTimeout)
        }
        return RestTemplate().apply {
            setRequestFactory(requestFactory)
            interceptors = listOf(RestTemplateMdcPropagationInterceptor())
        }
    }

    @Bean
    fun httpHrClient(props: HrClientProperties, hrRestTemplate: RestTemplate): HrClient = HttpHrClient(props, hrRestTemplate)
}

class HttpHrClient(
    private val props: HrClientProperties,
    private val restTemplate: RestTemplate,
) : HrClient {

    override fun getEmployeeSnapshot(employerId: EmployerId, employeeId: EmployeeId, asOfDate: LocalDate): EmployeeSnapshot? {
        val url = "${props.baseUrl}/employers/${employerId.value}/employees/${employeeId.value}/snapshot?asOf=$asOfDate"
        return restTemplate.getForObject<EmployeeSnapshot>(url)
    }

    override fun getPayPeriod(employerId: EmployerId, payPeriodId: String): PayPeriod? {
        val url = "${props.baseUrl}/employers/${employerId.value}/pay-periods/$payPeriodId"
        return restTemplate.getForObject<PayPeriod>(url)
    }

    override fun findPayPeriodByCheckDate(employerId: EmployerId, checkDate: LocalDate): PayPeriod? {
        val url = "${props.baseUrl}/employers/${employerId.value}/pay-periods/by-check-date?checkDate=$checkDate"
        return restTemplate.getForObject<PayPeriod>(url)
    }

    override fun getGarnishmentOrders(employerId: EmployerId, employeeId: EmployeeId, asOfDate: LocalDate): List<com.example.uspayroll.payroll.model.garnishment.GarnishmentOrder> {
        val url = "${props.baseUrl}/employers/${employerId.value}/employees/${employeeId.value}/garnishments?asOf=$asOfDate"
        val dtoArray = restTemplate.getForObject<Array<GarnishmentOrderDto>>(url)
            ?: return emptyList()
        return dtoArray.toList().map { it.toDomain() }
    }

    override fun recordGarnishmentWithholding(employerId: EmployerId, employeeId: EmployeeId, request: GarnishmentWithholdingRequest) {
        val url = "${props.baseUrl}/employers/${employerId.value}/employees/${employeeId.value}/garnishments/withholdings"
        restTemplate.postForLocation(url, request)
    }
}
