package com.example.uspayroll.filings.service

import com.example.uspayroll.filings.persistence.PaycheckLedgerRepository
import com.example.uspayroll.filings.persistence.PaycheckPaymentStatusRepository
import com.example.uspayroll.messaging.events.payments.PaycheckPaymentLifecycleStatus
import com.example.uspayroll.messaging.events.payments.PaycheckPaymentStatusChangedEvent
import com.example.uspayroll.messaging.events.reporting.PaycheckLedgerAction
import com.example.uspayroll.messaging.events.reporting.PaycheckLedgerAuditAggregates
import com.example.uspayroll.messaging.events.reporting.PaycheckLedgerEvent
import com.example.uspayroll.messaging.events.reporting.PaycheckLedgerTaxLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestConstructor
import org.springframework.test.context.TestPropertySource
import java.time.Instant

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:filings_test;MODE=PostgreSQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=true",
        "filings.kafka.enabled=false",
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class FilingsComputationServiceIT(
    private val filings: FilingsComputationService,
    private val ledger: PaycheckLedgerRepository,
    private val payments: PaycheckPaymentStatusRepository,
) {

    @Test
    fun `941 aggregates wages and reconciles payments`() {
        val employerId = "emp-1"
        val now = Instant.parse("2025-01-20T00:00:00Z")

        val p1 = PaycheckLedgerEvent(
            eventId = "evt-1",
            occurredAt = now,
            action = PaycheckLedgerAction.COMMITTED,
            employerId = employerId,
            employeeId = "e-1",
            payRunId = "run-1",
            payRunType = "REGULAR",
            runSequence = 1,
            payPeriodId = "pp-1",
            paycheckId = "pc-1",
            periodStartIso = "2025-01-01",
            periodEndIso = "2025-01-14",
            checkDateIso = "2025-01-15",
            currency = "USD",
            grossCents = 10_000_00L,
            netCents = 8_000_00L,
            audit = PaycheckLedgerAuditAggregates(
                cashGrossCents = 10_000_00L,
                grossTaxableCents = 10_000_00L,
                federalTaxableCents = 10_000_00L,
                stateTaxableCents = 10_000_00L,
                socialSecurityWagesCents = 10_000_00L,
                medicareWagesCents = 10_000_00L,
                supplementalWagesCents = 0L,
                futaWagesCents = 10_000_00L,
                employeeTaxCents = 2_000_00L,
                employerTaxCents = 800_00L,
                preTaxDeductionCents = 0L,
                postTaxDeductionCents = 0L,
                garnishmentCents = 0L,
            ),
            employeeTaxes = listOf(
                PaycheckLedgerTaxLine(
                    ruleId = "FED_WH",
                    jurisdictionType = "FEDERAL",
                    jurisdictionCode = "US",
                    description = "Federal withholding",
                    basisCents = 10_000_00L,
                    rate = 0.1,
                    amountCents = 1_000_00L,
                ),
                PaycheckLedgerTaxLine(
                    ruleId = "SS_EMP",
                    jurisdictionType = "FEDERAL",
                    jurisdictionCode = "SS",
                    description = "Social security",
                    basisCents = 10_000_00L,
                    rate = 0.062,
                    amountCents = 620_00L,
                ),
                PaycheckLedgerTaxLine(
                    ruleId = "MED_EMP",
                    jurisdictionType = "FEDERAL",
                    jurisdictionCode = "MED",
                    description = "Medicare",
                    basisCents = 10_000_00L,
                    rate = 0.0145,
                    amountCents = 145_00L,
                ),
            ),
            employerTaxes = listOf(
                PaycheckLedgerTaxLine(
                    ruleId = "SS_ER",
                    jurisdictionType = "FEDERAL",
                    jurisdictionCode = "SS",
                    description = "Social security employer",
                    basisCents = 10_000_00L,
                    rate = 0.062,
                    amountCents = 620_00L,
                ),
                PaycheckLedgerTaxLine(
                    ruleId = "MED_ER",
                    jurisdictionType = "FEDERAL",
                    jurisdictionCode = "MED",
                    description = "Medicare employer",
                    basisCents = 10_000_00L,
                    rate = 0.0145,
                    amountCents = 145_00L,
                ),
            ),
        )

        val p2 = p1.copy(
            eventId = "evt-2",
            employeeId = "e-2",
            paycheckId = "pc-2",
            netCents = 7_000_00L,
        )

        ledger.upsertFromEvent(p1)
        ledger.upsertFromEvent(p2)

        payments.upsertFromEvent(
            PaycheckPaymentStatusChangedEvent(
                eventId = "pay-evt-1",
                occurredAt = now,
                employerId = employerId,
                payRunId = "run-1",
                paycheckId = "pc-1",
                paymentId = "pmt-pc-1",
                status = PaycheckPaymentLifecycleStatus.SETTLED,
            ),
        )

        val f941 = filings.compute941(employerId, year = 2025, quarter = 1)

        assertEquals(20_000_00L, f941.cashGrossCents)
        assertEquals(20_000_00L, f941.federalTaxableCents)
        assertEquals(2_000_00L, f941.federalWithholdingCents)
        assertEquals(1_240_00L, f941.employeeSocialSecurityTaxCents)
        assertEquals(1_240_00L, f941.employerSocialSecurityTaxCents)

        assertEquals(2, f941.payments.paycheckCount)
        assertEquals(1, f941.payments.settledPaycheckCount)
        assertEquals(1, f941.payments.missingPaymentStatusCount)
        assertEquals(15_000_00L, f941.payments.expectedNetCents)
        assertEquals(8_000_00L, f941.payments.settledNetCents)
    }

    @Test
    fun `W2 and W3 are computed from annual ledger events`() {
        val employerId = "emp-1"
        val now = Instant.parse("2025-12-20T00:00:00Z")

        val evt = PaycheckLedgerEvent(
            eventId = "evt-w2-1",
            occurredAt = now,
            action = PaycheckLedgerAction.COMMITTED,
            employerId = employerId,
            employeeId = "e-1",
            payRunId = "run-10",
            payRunType = "REGULAR",
            runSequence = 1,
            payPeriodId = "pp-10",
            paycheckId = "pc-w2-1",
            periodStartIso = "2025-12-01",
            periodEndIso = "2025-12-14",
            checkDateIso = "2025-12-15",
            currency = "USD",
            grossCents = 1_000_00L,
            netCents = 900_00L,
            audit = PaycheckLedgerAuditAggregates(
                cashGrossCents = 1_000_00L,
                grossTaxableCents = 1_000_00L,
                federalTaxableCents = 1_000_00L,
                stateTaxableCents = 1_000_00L,
                socialSecurityWagesCents = 1_000_00L,
                medicareWagesCents = 1_000_00L,
                supplementalWagesCents = 0L,
                futaWagesCents = 1_000_00L,
                employeeTaxCents = 100_00L,
                employerTaxCents = 50_00L,
                preTaxDeductionCents = 0L,
                postTaxDeductionCents = 0L,
                garnishmentCents = 0L,
            ),
            employeeTaxes = listOf(
                PaycheckLedgerTaxLine(
                    ruleId = "FED_WH",
                    jurisdictionType = "FEDERAL",
                    jurisdictionCode = "US",
                    description = "Federal withholding",
                    basisCents = 1_000_00L,
                    rate = 0.1,
                    amountCents = 100_00L,
                ),
                PaycheckLedgerTaxLine(
                    ruleId = "SS_EMP",
                    jurisdictionType = "FEDERAL",
                    jurisdictionCode = "SS",
                    description = "Social security",
                    basisCents = 1_000_00L,
                    rate = 0.062,
                    amountCents = 62_00L,
                ),
                PaycheckLedgerTaxLine(
                    ruleId = "MED_EMP",
                    jurisdictionType = "FEDERAL",
                    jurisdictionCode = "MED",
                    description = "Medicare",
                    basisCents = 1_000_00L,
                    rate = 0.0145,
                    amountCents = 14_50L,
                ),
                PaycheckLedgerTaxLine(
                    ruleId = "CA_WH",
                    jurisdictionType = "STATE",
                    jurisdictionCode = "CA",
                    description = "CA withholding",
                    basisCents = 1_000_00L,
                    rate = 0.05,
                    amountCents = 50_00L,
                ),
            ),
        )

        ledger.upsertFromEvent(evt)

        val w2s = filings.computeW2s(employerId, 2025)
        assertEquals(1, w2s.size)
        assertEquals("e-1", w2s.single().employeeId)
        assertEquals(1_000_00L, w2s.single().wagesCents)
        assertEquals(100_00L, w2s.single().federalWithholdingCents)
        assertEquals(50_00L, w2s.single().stateWithholdingByState.getValue("CA"))

        val w3 = filings.computeW3(employerId, 2025)
        assertEquals(1, w3.employeeCount)
        assertEquals(1_000_00L, w3.totalWagesCents)
        assertEquals(100_00L, w3.totalFederalWithholdingCents)
    }
}
