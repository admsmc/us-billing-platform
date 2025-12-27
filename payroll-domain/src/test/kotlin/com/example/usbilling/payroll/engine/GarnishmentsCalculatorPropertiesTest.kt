package com.example.usbilling.payroll.engine

import com.example.usbilling.payroll.model.*
import com.example.usbilling.payroll.model.config.DeductionKind
import com.example.usbilling.payroll.model.config.DeductionPlan
import com.example.usbilling.payroll.model.garnishment.GarnishmentContext
import com.example.usbilling.payroll.model.garnishment.GarnishmentFormula
import com.example.usbilling.payroll.model.garnishment.GarnishmentOrder
import com.example.usbilling.payroll.model.garnishment.GarnishmentOrderId
import com.example.usbilling.shared.EmployeeId
import com.example.usbilling.shared.EmployerId
import com.example.usbilling.shared.Money
import com.example.usbilling.shared.PayRunId
import com.example.usbilling.shared.PaycheckId
import java.time.LocalDate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Lightweight property-style tests for GarnishmentsCalculator to exercise
 * invariants across a range of randomized inputs without introducing an
 * additional property-testing framework.
 */
class GarnishmentsCalculatorPropertiesTest {

    private fun randomMoney(rng: Random, maxCents: Long = 20_000_00L): Money = Money(rng.nextLong(0, maxCents))

    private fun randomPercent(rng: Random): Percent = Percent(rng.nextDouble(from = 0.0, until = 0.6)) // up to 60%

    private fun randomOrder(rng: Random, idSuffix: Int): GarnishmentOrder {
        val employer = EmployerId("EMP-PROP")
        val orderId = GarnishmentOrderId("ORDER-PROP-$idSuffix")
        val type = if (rng.nextBoolean()) {
            com.example.usbilling.payroll.model.garnishment.GarnishmentType.CREDITOR_GARNISHMENT
        } else {
            com.example.usbilling.payroll.model.garnishment.GarnishmentType.CHILD_SUPPORT
        }

        val formula = if (rng.nextBoolean()) {
            GarnishmentFormula.PercentOfDisposable(randomPercent(rng))
        } else {
            GarnishmentFormula.LesserOfPercentOrAmount(
                percent = randomPercent(rng),
                amount = randomMoney(rng, maxCents = 5_000_00L),
            )
        }

        return GarnishmentOrder(
            orderId = orderId,
            planId = "GARN_PROP_$idSuffix",
            type = type,
            issuingJurisdiction = TaxJurisdiction(TaxJurisdictionType.STATE, "CA"),
            caseNumber = null,
            servedDate = null,
            endDate = null,
            priorityClass = rng.nextInt(0, 3),
            sequenceWithinClass = rng.nextInt(0, 3),
            formula = formula,
            protectedEarningsRule = null,
            arrearsBefore = null,
            lifetimeCap = null,
        )
    }

    private fun randomInput(rng: Random, orders: List<GarnishmentOrder>): Pair<PaycheckInput, Map<DeductionCode, DeductionPlan>> {
        val employerId = EmployerId("EMP-PROP")
        val employeeId = EmployeeId("EE-PROP")
        val period = PayPeriod(
            id = "PROP-PERIOD",
            employerId = employerId,
            dateRange = LocalDateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 14)),
            checkDate = LocalDate.of(2025, 1, 15),
            frequency = PayFrequency.BIWEEKLY,
        )
        val snapshot = EmployeeSnapshot(
            employerId = employerId,
            employeeId = employeeId,
            homeState = "CA",
            workState = "CA",
            filingStatus = if (rng.nextBoolean()) FilingStatus.SINGLE else FilingStatus.MARRIED,
            baseCompensation = BaseCompensation.Salaried(
                annualSalary = Money(52_000_00L),
                frequency = period.frequency,
            ),
        )

        val plans = orders.associate { order ->
            val plan = DeductionPlan(
                id = order.planId,
                name = "Plan ${order.planId}",
                kind = DeductionKind.GARNISHMENT,
            )
            DeductionCode(order.orderId.value) to plan
        }

        val input = PaycheckInput(
            paycheckId = PaycheckId("CHK-PROP"),
            payRunId = PayRunId("RUN-PROP"),
            employerId = employerId,
            employeeId = employeeId,
            period = period,
            employeeSnapshot = snapshot,
            timeSlice = TimeSlice(
                period = period,
                regularHours = 80.0,
                overtimeHours = 0.0,
            ),
            taxContext = TaxContext(),
            priorYtd = YtdSnapshot(year = 2025),
            garnishments = GarnishmentContext(orders = orders),
        )

        return input to plans
    }

    @Test
    fun `protected earnings floors never increase requested garnishments and net never goes below floor`() {
        val rng = Random(42)

        repeat(50) {
            // Randomize a small set of orders; half of them get a protected floor.
            val orders = (1..3).map { idx ->
                val order = randomOrder(rng, idx)
                if (rng.nextBoolean()) {
                    val floor = randomMoney(rng, maxCents = 5_000_00L)
                    order.copy(
                        protectedEarningsRule = com.example.usbilling.payroll.model.garnishment.ProtectedEarningsRule.FixedFloor(
                            floor,
                        ),
                    )
                } else {
                    order
                }
            }

            val (input, plansByCode) = randomInput(rng, orders)
            val gross = Money(2_000_00L + rng.nextLong(0, 3_000_00L))

            // Simple synthetic taxes and pre-tax deductions.
            val preTax = listOf(
                DeductionLine(DeductionCode("PRE"), "Pre", randomMoney(rng, maxCents = 1_000_00L)),
            )
            val employeeTaxes = listOf(
                TaxLine(
                    ruleId = "TAX1",
                    jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "US"),
                    description = "Tax",
                    basis = Money(0L),
                    rate = null,
                    amount = randomMoney(rng, maxCents = 1_000_00L),
                ),
            )

            val result = GarnishmentsCalculator.computeGarnishments(
                input = input,
                gross = gross,
                employeeTaxes = employeeTaxes,
                preTaxDeductions = preTax,
                plansByCode = plansByCode,
            )

            val totalGarnished = result.garnishments.sumOf { it.amount.amount }
            assertTrue(totalGarnished >= 0L, "Total garnishment should be non-negative")

            val protectedSteps = result.traceSteps.filterIsInstance<TraceStep.ProtectedEarningsApplied>()
            protectedSteps.forEach { step ->
                assertTrue(
                    step.adjustedCents <= step.requestedCents,
                    "Protected earnings floor must not increase requested amount",
                )
            }

            // For each protected-order garnishment, ensure net cash after
            // garnishments is not below the floor.
            val floorsByOrder = protectedSteps.associateBy({ it.orderId }, { it.floorCents })
            val gaByOrder = result.traceSteps.filterIsInstance<TraceStep.GarnishmentApplied>()
                .associateBy { it.orderId }

            floorsByOrder.forEach { (orderId, floor) ->
                val ga = gaByOrder[orderId]
                if (ga != null) {
                    val netAfterGarnishments = ga.disposableAfterCents
                    assertTrue(
                        netAfterGarnishments >= floor,
                        "Net cash after garnishments ($netAfterGarnishments) should be >= floor $floor for $orderId",
                    )
                }
            }
        }
    }
}
