package com.example.uspayroll.worker

import com.example.uspayroll.shared.LocalityCode

/**
 * Resolves canonical locality codes (e.g. DETROIT, GRAND_RAPIDS, LANSING)
 * from high-level work location fields.
 *
 * This abstraction allows HR/address logic to evolve independently from tax
 * rule selection: worker-service is responsible for turning work state/city
 * into one or more [LocalityCode]s which are then passed to the tax catalog.
 */
interface LocalityResolver {
    fun resolve(workState: String?, workCity: String?): List<LocalityCode>
}

/**
 * Simple, hard-coded implementation for initial Michigan city support.
 *
 * Maps specific (state, city) pairs into canonical locality codes used by
 * tax and labor configs. All other combinations return an empty list.
 */
@org.springframework.stereotype.Component
class MichiganLocalityResolver : LocalityResolver {

    override fun resolve(workState: String?, workCity: String?): List<LocalityCode> {
        if (!"MI".equals(workState, ignoreCase = true)) return emptyList()
        val city = workCity?.trim()?.lowercase() ?: return emptyList()

        return when (city) {
            "detroit" -> listOf(LocalityCode("DETROIT"))
            "grand rapids" -> listOf(LocalityCode("GRAND_RAPIDS"))
            "lansing" -> listOf(LocalityCode("LANSING"))
            else -> emptyList()
        }
    }
}
