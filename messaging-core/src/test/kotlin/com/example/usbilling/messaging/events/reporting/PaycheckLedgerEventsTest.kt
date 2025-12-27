package com.example.usbilling.messaging.events.reporting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class PaycheckLedgerEventsTest {

    @Test
    fun `ledger event is constructible and carries expected fields`() {
        val audit = PaycheckLedgerAuditAggregates(
            cashGrossCents = 5_000_00L,
            grossTaxableCents = 5_000_00L,
            federalTaxableCents = 5_000_00L,
            stateTaxableCents = 5_000_00L,
            socialSecurityWagesCents = 5_000_00L,
            medicareWagesCents = 5_000_00L,
            supplementalWagesCents = 0L,
            futaWagesCents = 5_000_00L,
            employeeTaxCents = 800_00L,
            employerTaxCents = 300_00L,
            preTaxDeductionCents = 100_00L,
            postTaxDeductionCents = 0L,
            garnishmentCents = 0L,
        )

        val evt = PaycheckLedgerEvent(
            eventId = "evt-1",
            occurredAt = Instant.parse("2025-01-01T00:00:00Z"),
            action = PaycheckLedgerAction.COMMITTED,
            employerId = "emp-1",
            employeeId = "e-1",
            payRunId = "run-1",
            payRunType = "REGULAR",
            runSequence = 1,
            payPeriodId = "pp-1",
            paycheckId = "pc-1",
            periodStartIso = "2025-01-01",
            periodEndIso = "2025-01-15",
            checkDateIso = "2025-01-16",
            currency = "USD",
            grossCents = 5_000_00L,
            netCents = 4_000_00L,
            audit = audit,
            earnings = listOf(
                PaycheckLedgerEarningLine(
                    code = "REG",
                    category = "REGULAR",
                    description = "Regular",
                    units = 80.0,
                    rateCents = 62_50L,
                    amountCents = 5_000_00L,
                ),
            ),
            employeeTaxes = listOf(
                PaycheckLedgerTaxLine(
                    ruleId = "federal-income-2025",
                    jurisdictionType = "FEDERAL",
                    jurisdictionCode = "US",
                    description = "Federal income tax",
                    basisCents = 5_000_00L,
                    rate = 0.1,
                    amountCents = 500_00L,
                ),
            ),
            deductions = listOf(
                PaycheckLedgerDeductionLine(
                    code = "401K",
                    description = "401k",
                    amountCents = 100_00L,
                ),
            ),
            employerContributions = listOf(
                PaycheckLedgerEmployerContributionLine(
                    code = "401K_MATCH",
                    description = "401k match",
                    amountCents = 50_00L,
                ),
            ),
        )

        assertEquals(PaycheckLedgerAction.COMMITTED, evt.action)
        assertEquals("emp-1", evt.employerId)
        assertEquals(audit, evt.audit)
        assertEquals(1, evt.earnings.size)
        assertEquals("REG", evt.earnings.first().code)
    }
}

// end
