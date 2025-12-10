package com.example.uspayroll.worker

import com.example.uspayroll.shared.LocalityCode
import kotlin.test.Test
import kotlin.test.assertEquals

class MichiganLocalityResolverTest {

    private val resolver: LocalityResolver = MichiganLocalityResolver()

    @Test
    fun `resolves Detroit Grand Rapids and Lansing for MI`() {
        assertEquals(listOf(LocalityCode("DETROIT")), resolver.resolve("MI", "Detroit"))
        assertEquals(listOf(LocalityCode("GRAND_RAPIDS")), resolver.resolve("MI", "Grand Rapids"))
        assertEquals(listOf(LocalityCode("LANSING")), resolver.resolve("MI", "Lansing"))
    }

    @Test
    fun `returns empty list for non-MI states or unknown cities`() {
        assertEquals(emptyList(), resolver.resolve("CA", "Detroit"))
        assertEquals(emptyList(), resolver.resolve("MI", "Ann Arbor"))
        assertEquals(emptyList(), resolver.resolve("MI", null))
        assertEquals(emptyList(), resolver.resolve(null, "Detroit"))
    }
}
