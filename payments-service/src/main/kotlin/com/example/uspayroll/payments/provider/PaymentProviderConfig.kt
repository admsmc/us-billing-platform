package com.example.uspayroll.payments.provider

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@ConfigurationProperties(prefix = "payments.provider")
data class PaymentProviderProperties(
    /** Provider implementation key (e.g. sandbox, ach, wire). */
    var type: String = "sandbox",
)

@Configuration
@EnableConfigurationProperties(PaymentProviderProperties::class)
class PaymentProviderConfig {
    @Bean
    fun paymentProvider(props: PaymentProviderProperties, sandbox: SandboxPaymentProvider): PaymentProvider = when (props.type.lowercase()) {
        "sandbox" -> sandbox
        else -> throw IllegalArgumentException("Unsupported payments.provider.type='${props.type}'")
    }
}
