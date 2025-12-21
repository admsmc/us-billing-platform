package com.example.uspayroll.worker.client

import com.example.uspayroll.hr.client.HrClientProperties
import com.example.uspayroll.web.RestTemplateMdcPropagationInterceptor
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.web.client.RestTemplate

@Configuration
class WorkerRestTemplateConfig {

    @Bean("hrRestTemplate")
    fun hrRestTemplate(messageConverter: MappingJackson2HttpMessageConverter, hrProps: HrClientProperties, builder: RestTemplateBuilder, meterRegistry: MeterRegistry): RestTemplate =
        build(builder, messageConverter, hrProps.connectTimeout, hrProps.readTimeout, meterRegistry, "hr")

    @Bean("taxRestTemplate")
    fun taxRestTemplate(messageConverter: MappingJackson2HttpMessageConverter, taxProps: TaxClientProperties, builder: RestTemplateBuilder, meterRegistry: MeterRegistry): RestTemplate =
        build(builder, messageConverter, taxProps.connectTimeout, taxProps.readTimeout, meterRegistry, "tax")

    @Bean("laborRestTemplate")
    fun laborRestTemplate(messageConverter: MappingJackson2HttpMessageConverter, laborProps: LaborClientProperties, builder: RestTemplateBuilder, meterRegistry: MeterRegistry): RestTemplate =
        build(builder, messageConverter, laborProps.connectTimeout, laborProps.readTimeout, meterRegistry, "labor")

    @Bean("timeRestTemplate")
    fun timeRestTemplate(messageConverter: MappingJackson2HttpMessageConverter, timeProps: TimeClientProperties, builder: RestTemplateBuilder, meterRegistry: MeterRegistry): RestTemplate =
        build(builder, messageConverter, timeProps.connectTimeout, timeProps.readTimeout, meterRegistry, "time")

    @Bean("orchestratorRestTemplate")
    fun orchestratorRestTemplate(
        messageConverter: MappingJackson2HttpMessageConverter,
        props: OrchestratorClientProperties,
        builder: RestTemplateBuilder,
        meterRegistry: MeterRegistry,
    ): RestTemplate = build(builder, messageConverter, props.connectTimeout, props.readTimeout, meterRegistry, "orchestrator")

    private fun build(
        builder: RestTemplateBuilder,
        messageConverter: MappingJackson2HttpMessageConverter,
        connectTimeout: java.time.Duration,
        readTimeout: java.time.Duration,
        meterRegistry: MeterRegistry,
        client: String,
    ): RestTemplate {
        val requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(connectTimeout)
            setReadTimeout(readTimeout)
        }

        val restTemplate = builder
            .messageConverters(messageConverter)
            .build()

        restTemplate.setRequestFactory(requestFactory)
        restTemplate.interceptors = restTemplate.interceptors + RestTemplateMetricsInterceptor(meterRegistry, client) + RestTemplateMdcPropagationInterceptor()
        return restTemplate
    }
}
