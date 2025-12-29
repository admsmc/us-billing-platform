package com.example.usbilling.billing.engine

import com.example.usbilling.shared.Money

/**
 * Aggregated charge totals.
 */
data class ChargeAggregation(
    val totalCharges: Money,
    val totalCredits: Money,
    val amountDue: Money
)
