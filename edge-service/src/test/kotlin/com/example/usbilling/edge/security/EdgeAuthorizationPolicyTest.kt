package com.example.usbilling.edge.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EdgeAuthorizationPolicyTest {

    @Test
    fun `returns required scope for first matching rule`() {
        val policy = EdgeAuthorizationPolicy(
            rules = listOf(
                EdgeAuthorizationPolicy.Rule(
                    id = "first",
                    pathRegex = Regex("^/benchmarks/.*$"),
                    requiredScope = "payroll:bench",
                ),
                EdgeAuthorizationPolicy.Rule(
                    id = "second",
                    pathRegex = Regex("^/benchmarks/.*$"),
                    requiredScope = "payroll:admin",
                ),
            ),
        )

        assertEquals("payroll:bench", policy.requiredScope("/benchmarks/employers/EMP1/hr-backed-pay-period", "POST"))
    }

    @Test
    fun `returns null when no rule matches`() {
        val policy = EdgeAuthorizationPolicy.default()
        assertEquals(null, policy.requiredScope("/employers/EMP1/payruns/finalize", "POST"))
    }
}
