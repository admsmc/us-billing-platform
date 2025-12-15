package com.example.uspayroll.orchestrator.location

import com.example.uspayroll.shared.LocalityCode

/**
 * Resolves canonical locality codes (e.g. DETROIT, GRAND_RAPIDS) from high-level
 * work location fields.
 *
 * Orchestrator-service needs this when fetching tax/labor context to select
 * locality-specific rules.
 */
interface LocalityResolver {
    fun resolve(workState: String?, workCity: String?): List<LocalityCode>
}

@org.springframework.stereotype.Component
class MichiganLocalityResolver : LocalityResolver {

    override fun resolve(workState: String?, workCity: String?): List<LocalityCode> {
        if (!"MI".equals(workState, ignoreCase = true)) return emptyList()
        val raw = workCity?.trim().orEmpty()
        if (raw.isBlank()) return emptyList()

        val parts = raw.split(',', ';')
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }

        val out = ArrayList<LocalityCode>(parts.size)
        for (city in parts) {
            val codes = when (city) {
                "detroit" -> listOf(LocalityCode("DETROIT"))
                "grand rapids" -> listOf(LocalityCode("GRAND_RAPIDS"))
                "lansing" -> listOf(LocalityCode("LANSING"))
                else -> emptyList()
            }
            out.addAll(codes)
        }

        return out.distinctBy { it.value }
    }
}
