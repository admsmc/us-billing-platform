package com.example.uspayroll.timeingestion.rules

import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.time.model.TipAllocationRuleSet
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Component
class TipAllocationRuleSetResolver(
    private val objectMapper: ObjectMapper,
) {
    private val cache = ConcurrentHashMap<String, TipAllocationRuleSet>()

    fun resolve(employerId: EmployerId, workState: String?): TipAllocationRuleSet {
        val state = (workState ?: "").trim().uppercase()
        val key = "${employerId.value}|$state"

        return cache.computeIfAbsent(key) {
            val paths = listOf(
                "tip-rules/employers/${employerId.value}/$state.json",
                "tip-rules/employers/${employerId.value}/default.json",
                "tip-rules/states/$state.json",
                "tip-rules/default.json",
            )

            val resource = paths
                .map { ClassPathResource(it) }
                .firstOrNull { it.exists() }
                ?: error("No tip allocation rule resources found (expected tip-rules/default.json to exist)")

            resource.inputStream.use { input ->
                objectMapper.readValue(input, TipAllocationRuleSet::class.java)
            }
        }
    }
}
