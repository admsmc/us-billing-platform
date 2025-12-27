package com.example.usbilling.worker.client

import com.example.usbilling.payroll.model.TaxContext
import com.example.usbilling.shared.EmployerId
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.core.RedisTemplate
import java.time.Duration
import java.time.LocalDate

@ConfigurationProperties(prefix = "cache")
data class CacheProperties(
    var enabled: Boolean = true,
    var taxTtlHours: Long = 24,
    var laborTtlHours: Long = 24,
)

@Configuration
class CachedTaxClientConfig {

    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "cache", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun cachedTaxClient(
        httpTaxClient: TaxClient,
        redisTemplate: RedisTemplate<String, String>,
        @org.springframework.beans.factory.annotation.Qualifier("cacheObjectMapper") cacheObjectMapper: ObjectMapper,
        cacheProperties: CacheProperties,
        meterRegistry: MeterRegistry,
    ): TaxClient = CachedTaxClient(
        delegate = httpTaxClient,
        redisTemplate = redisTemplate,
        objectMapper = cacheObjectMapper,
        ttl = Duration.ofHours(cacheProperties.taxTtlHours),
        meterRegistry = meterRegistry,
    )
}

/**
 * Redis-backed caching decorator for [TaxClient].
 *
 * Reduces tax-service calls from N per payrun to ~1 per unique employer/date combination.
 * Expected impact: 30-50% reduction in per-employee processing time for large payruns.
 *
 * Cache key format: `tax:{employerId}:{asOf}:{residentState}:{workState}:{localityCodes}`
 * TTL: 24 hours (tax rules rarely change mid-day)
 */
class CachedTaxClient(
    private val delegate: TaxClient,
    private val redisTemplate: RedisTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val ttl: Duration,
    private val meterRegistry: MeterRegistry,
) : TaxClient {

    private val logger = LoggerFactory.getLogger(CachedTaxClient::class.java)

    override fun getTaxContext(
        employerId: EmployerId,
        asOfDate: LocalDate,
        residentState: String?,
        workState: String?,
        localityCodes: List<String>,
    ): TaxContext {
        val cacheKey = buildCacheKey(employerId, asOfDate, residentState, workState, localityCodes)

        // Check cache first
        val cachedJson = redisTemplate.opsForValue().get(cacheKey)
        if (cachedJson != null) {
            meterRegistry.counter("uspayroll.cache.tax.hit").increment()
            logger.debug("cache.hit key={}", cacheKey)
            return objectMapper.readValue(cachedJson, TaxContext::class.java)
        }

        // Cache miss - fetch from service
        meterRegistry.counter("uspayroll.cache.tax.miss").increment()
        logger.debug("cache.miss key={}", cacheKey)

        val fresh = delegate.getTaxContext(employerId, asOfDate, residentState, workState, localityCodes)

        // Store in cache
        try {
            val json = objectMapper.writeValueAsString(fresh)
            redisTemplate.opsForValue().set(cacheKey, json, ttl)
            logger.debug("cache.set key={} ttl={}", cacheKey, ttl)
        } catch (e: Exception) {
            // Cache failures should not break the request
            logger.warn("cache.set.failed key={} error={}", cacheKey, e.message)
            meterRegistry.counter("uspayroll.cache.tax.error", "operation", "set").increment()
        }

        return fresh
    }

    private fun buildCacheKey(
        employerId: EmployerId,
        asOfDate: LocalDate,
        residentState: String?,
        workState: String?,
        localityCodes: List<String>,
    ): String {
        val localityPart = if (localityCodes.isEmpty()) "" else localityCodes.sorted().joinToString(",")
        return "tax:${employerId.value}:$asOfDate:${residentState.orEmpty()}:${workState.orEmpty()}:$localityPart"
    }
}
