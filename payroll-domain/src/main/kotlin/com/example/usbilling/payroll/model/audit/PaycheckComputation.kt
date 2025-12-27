package com.example.usbilling.payroll.model.audit

import com.example.usbilling.payroll.model.PaycheckResult

/**
 * Result of an authoritative paycheck computation.
 *
 * - [paycheck] is the business output used for payment/reporting.
 * - [audit] is the persistable enterprise audit artifact.
 */
data class PaycheckComputation(
    val paycheck: PaycheckResult,
    val audit: PaycheckAudit,
)
