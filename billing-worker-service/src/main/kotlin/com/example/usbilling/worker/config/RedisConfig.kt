package com.example.usbilling.worker.config

import com.example.usbilling.payroll.jackson.PayrollDomainKeyJacksonModule
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.StringRedisSerializer

@Configuration
@EnableConfigurationProperties(com.example.usbilling.worker.client.CacheProperties::class)
class RedisConfig {

    /**
     * Primary ObjectMapper for HTTP clients (plain JSON, no polymorphic typing).
     */
    @Bean
    @org.springframework.context.annotation.Primary
    fun objectMapper(): ObjectMapper = ObjectMapper().apply {
        // Register Kotlin module
        registerModule(
            KotlinModule.Builder()
                .withReflectionCacheSize(512)
                .configure(KotlinFeature.NullToEmptyCollection, false)
                .configure(KotlinFeature.NullToEmptyMap, false)
                .configure(KotlinFeature.NullIsSameAsDefault, false)
                .configure(KotlinFeature.SingletonSupport, false)
                .configure(KotlinFeature.StrictNullChecks, false)
                .build(),
        )
        // Register Java 8 date/time module
        registerModule(JavaTimeModule())
        // Register PayrollDomainKeyJacksonModule for map key serialization
        registerModule(PayrollDomainKeyJacksonModule.module())
        // Disable writing dates as timestamps
        disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    }

    /**
     * Specialized ObjectMapper for Redis caching with polymorphic type handling.
     * Includes @class type information for sealed classes (TaxRule, TaxBasis, etc.).
     * Only used for Redis caching, NOT for HTTP or RabbitMQ.
     */
    @Bean("cacheObjectMapper")
    @ConditionalOnProperty(prefix = "cache", name = ["enabled"], havingValue = "true", matchIfMissing = false)
    fun cacheObjectMapper(): ObjectMapper = ObjectMapper().apply {
        // Register Kotlin module
        registerModule(
            KotlinModule.Builder()
                .withReflectionCacheSize(512)
                .configure(KotlinFeature.NullToEmptyCollection, false)
                .configure(KotlinFeature.NullToEmptyMap, false)
                .configure(KotlinFeature.NullIsSameAsDefault, false)
                .configure(KotlinFeature.SingletonSupport, false)
                .configure(KotlinFeature.StrictNullChecks, false)
                .build(),
        )
        // Register Java 8 date/time module
        registerModule(JavaTimeModule())
        // Register PayrollDomainKeyJacksonModule for map key serialization
        registerModule(PayrollDomainKeyJacksonModule.module())
        // Disable writing dates as timestamps
        disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

        // Enable polymorphic type handling for sealed classes
        val ptv = BasicPolymorphicTypeValidator.builder()
            .allowIfSubType("com.example.usbilling")
            .allowIfSubType("java.util")
            .build()
        activateDefaultTyping(ptv, ObjectMapper.DefaultTyping.NON_FINAL)
    }

    @Bean
    @ConditionalOnProperty(prefix = "cache", name = ["enabled"], havingValue = "true", matchIfMissing = false)
    fun redisTemplate(connectionFactory: RedisConnectionFactory): RedisTemplate<String, String> {
        val template = RedisTemplate<String, String>()
        template.connectionFactory = connectionFactory

        // Use String serializers for both keys and values
        // Values are JSON strings serialized by Jackson
        template.keySerializer = StringRedisSerializer()
        template.valueSerializer = StringRedisSerializer()
        template.hashKeySerializer = StringRedisSerializer()
        template.hashValueSerializer = StringRedisSerializer()

        template.afterPropertiesSet()
        return template
    }
}
