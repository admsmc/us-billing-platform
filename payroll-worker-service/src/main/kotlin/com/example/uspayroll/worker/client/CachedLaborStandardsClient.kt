package com.example.uspayroll.worker.client

import com.example.uspayroll.payroll.model.LaborStandardsContext
import com.example.uspayroll.shared.EmployerId
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.core.RedisTemplate
import java.time.Duration
import java.time.LocalDate

@Configuration
class CachedLaborStandardsClientConfig {

    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "cache", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun cachedLaborStandardsClient(
        httpLaborStandardsClient: LaborStandardsClient,
        redisTemplate: RedisTemplate<String, String>,
        @org.springframework.beans.factory.annotation.Qualifier("cacheObjectMapper") cacheObjectMapper: ObjectMapper,
        cacheProperties: CacheProperties,
        meterRegistry: MeterRegistry,
    ): LaborStandardsClient = CachedLaborStandardsClient(
        delegate = httpLaborStandardsClient,
        redisTemplate = redisTemplate,
        objectMapper = cacheObjectMapper,
        ttl = Duration.ofHours(cacheProperties.laborTtlHours),
        meterRegistry = meterRegistry,
    )
}

/**
 * Redis-backed caching decorator for [LaborStandardsClient].
 *
 * Reduces labor-service calls from N per payrun to ~1 per unique employer/state/date combination.
 * Expected impact: 30-50% reduction in per-employee processing time for large payruns.
 *
 * Cache key format: `labor:{employerId}:{asOf}:{workState}:{homeState}:{localityCodes}`
 * TTL: 24 hours (labor standards rarely change mid-day)
 */
class CachedLaborStandardsClient(
    private val delegate: LaborStandardsClient,
    private val redisTemplate: RedisTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val ttl: Duration,
    private val meterRegistry: MeterRegistry,
) : LaborStandardsClient {

    private val logger = LoggerFactory.getLogger(CachedLaborStandardsClient::class.java)

    override fun getLaborStandards(
        employerId: EmployerId,
        asOfDate: LocalDate,
        workState: String?,
        homeState: String?,
        localityCodes: List<String>,
    ): LaborStandardsContext? {
        if (workState == null) return null

        val cacheKey = buildCacheKey(employerId, asOfDate, workState, homeState, localityCodes)

        // Check cache first
        val cachedJson = redisTemplate.opsForValue().get(cacheKey)
        if (cachedJson != null) {
            meterRegistry.counter("uspayroll.cache.labor.hit").increment()
            logger.debug("cache.hit key={}", cacheKey)

            // Handle null sentinel value
            if (cachedJson == "null") {
                return null
            }

            return objectMapper.readValue(cachedJson, LaborStandardsContext::class.java)
        }

        // Cache miss - fetch from service
        meterRegistry.counter("uspayroll.cache.labor.miss").increment()
        logger.debug("cache.miss key={}", cacheKey)

        val fresh = delegate.getLaborStandards(employerId, asOfDate, workState, homeState, localityCodes)

        // Store in cache (including null results to avoid repeated 404s)
        try {
            val json = if (fresh == null) {
                "null"
            } else {
                objectMapper.writeValueAsString(fresh)
            }
            redisTemplate.opsForValue().set(cacheKey, json, ttl)
            logger.debug("cache.set key={} ttl={} null={}", cacheKey, ttl, fresh == null)
        } catch (e: Exception) {
            // Cache failures should not break the request
            logger.warn("cache.set.failed key={} error={}", cacheKey, e.message)
            meterRegistry.counter("uspayroll.cache.labor.error", "operation", "set").increment()
        }

        return fresh
    }

    private fun buildCacheKey(
        employerId: EmployerId,
        asOfDate: LocalDate,
        workState: String?,
        homeState: String?,
        localityCodes: List<String>,
    ): String {
        val localityPart = if (localityCodes.isEmpty()) "" else localityCodes.sorted().joinToString(",")
        return "labor:${employerId.value}:$asOfDate:${workState.orEmpty()}:${homeState.orEmpty()}:$localityPart"
    }
}
