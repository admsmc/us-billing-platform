package com.example.uspayroll.edge.security

/**
 * Explicit authorization policy for edge-service.
 *
 * This is intentionally simple (in-repo) and evaluates an ordered list of rules.
 * When no rule matches, callers should fall back to heuristic defaults.
 */
class EdgeAuthorizationPolicy(
    private val rules: List<Rule>,
) {

    data class Rule(
        val id: String,
        val pathRegex: Regex,
        val methods: Set<String> = emptySet(),
        val requiredScope: String,
    ) {
        fun matches(path: String, method: String): Boolean {
            if (!pathRegex.matches(path)) return false
            if (methods.isEmpty()) return true
            return method.uppercase() in methods
        }
    }

    fun requiredScope(path: String, method: String): String? {
        val m = method.uppercase()
        return rules.firstOrNull { it.matches(path, m) }?.requiredScope
    }

    companion object {
        fun default(): EdgeAuthorizationPolicy = EdgeAuthorizationPolicy(
            rules = listOf(
                Rule(
                    id = "internal_ops",
                    pathRegex = Regex("^/internal/.*$"),
                    requiredScope = "payroll:replay",
                ),
                Rule(
                    id = "benchmarks",
                    pathRegex = Regex("^/benchmarks/.*$"),
                    requiredScope = "payroll:bench",
                ),
                Rule(
                    id = "legacy_jobs",
                    pathRegex = Regex("^/jobs/.*$"),
                    requiredScope = "payroll:ops",
                ),
            ),
        )
    }
}
