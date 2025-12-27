package com.example.usbilling.orchestrator.client

import com.example.usbilling.hr.client.HrClientProperties
import com.example.usbilling.web.RestTemplateMdcPropagationInterceptor
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestTemplate

@Configuration
class OrchestratorRestTemplateConfig {

    @Bean("hrRestTemplate")
    fun hrRestTemplate(props: HrClientProperties, builder: RestTemplateBuilder, meterRegistry: MeterRegistry): RestTemplate = build(builder, props.connectTimeout, props.readTimeout, meterRegistry, "hr")

    @Bean("taxRestTemplate")
    fun taxRestTemplate(props: TaxClientProperties, builder: RestTemplateBuilder, meterRegistry: MeterRegistry): RestTemplate = build(builder, props.connectTimeout, props.readTimeout, meterRegistry, "tax")

    @Bean("laborRestTemplate")
    fun laborRestTemplate(props: LaborClientProperties, builder: RestTemplateBuilder, meterRegistry: MeterRegistry): RestTemplate = build(builder, props.connectTimeout, props.readTimeout, meterRegistry, "labor")

    @Bean("timeRestTemplate")
    fun timeRestTemplate(props: TimeClientProperties, builder: RestTemplateBuilder, meterRegistry: MeterRegistry): RestTemplate = build(builder, props.connectTimeout, props.readTimeout, meterRegistry, "time")

    @Bean("paymentsRestTemplate")
    fun paymentsRestTemplate(props: PaymentsQueryClientProperties, builder: RestTemplateBuilder, meterRegistry: MeterRegistry): RestTemplate = build(builder, props.connectTimeout, props.readTimeout, meterRegistry, "payments")

    private fun build(builder: RestTemplateBuilder, connectTimeout: java.time.Duration, readTimeout: java.time.Duration, meterRegistry: MeterRegistry, client: String): RestTemplate {
        val requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(connectTimeout)
            setReadTimeout(readTimeout)
        }

        val restTemplate = builder.build()
        restTemplate.setRequestFactory(requestFactory)
        restTemplate.interceptors = restTemplate.interceptors + RestTemplateMetricsInterceptor(meterRegistry, client) + RestTemplateMdcPropagationInterceptor()
        return restTemplate
    }
}
