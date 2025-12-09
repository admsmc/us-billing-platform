package com.example.uspayroll.payroll.model

/**
 * Represents the portion of a pay period that is actually worked or payable
 * for a salaried employee. This is intentionally simple for now and expressed
 * as a fraction in the range [0.0, 1.0].
 */
data class Proration(
    val fraction: Double,
) {
    init {
        require(fraction >= 0.0) { "proration fraction must be >= 0.0, was $fraction" }
        require(fraction <= 1.0) { "proration fraction must be <= 1.0, was $fraction" }
    }
}
