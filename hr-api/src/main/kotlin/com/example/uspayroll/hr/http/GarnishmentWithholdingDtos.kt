package com.example.uspayroll.hr.http

import com.example.uspayroll.shared.Money
import java.time.LocalDate

/**
 * Payload sent from worker-service back to hr-service to record the amount
 * withheld for each garnishment order in a given paycheck.
 */
data class GarnishmentWithholdingEvent(
    val orderId: String,
    val paycheckId: String,
    val payRunId: String?,
    val checkDate: LocalDate,
    val withheld: Money,
    val netPay: Money,
)

data class GarnishmentWithholdingRequest(
    val events: List<GarnishmentWithholdingEvent>,
)
