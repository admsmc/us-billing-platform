package com.example.usbilling.timeingestion.rules

import com.example.usbilling.shared.EmployerId
import com.example.usbilling.time.model.OvertimeRuleSet
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Component
class TimeRuleSetResolver(
    private val objectMapper: ObjectMapper,
) {
    private val cache = ConcurrentHashMap<String, OvertimeRuleSet>()

    fun resolve(employerId: EmployerId, workState: String?): OvertimeRuleSet {
        val state = (workState ?: "").trim().uppercase()
        val key = "${employerId.value}|$state"

        return cache.computeIfAbsent(key) {
            val paths = listOf(
                "time-rules/employers/${employerId.value}/$state.json",
                "time-rules/states/$state.json",
                "time-rules/default.json",
            )

            val resource = paths
                .map { ClassPathResource(it) }
                .firstOrNull { it.exists() }
                ?: error("No time rule resources found (expected default.json to exist)")

            resource.inputStream.use { input ->
                objectMapper.readValue(input, OvertimeRuleSet::class.java)
            }
        }
    }
}
