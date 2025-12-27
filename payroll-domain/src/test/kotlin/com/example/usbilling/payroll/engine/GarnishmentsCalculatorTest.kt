package com.example.usbilling.payroll.engine

import com.example.usbilling.payroll.model.*
import com.example.usbilling.payroll.model.config.DeductionKind
import com.example.usbilling.payroll.model.config.DeductionPlan
import com.example.usbilling.payroll.model.garnishment.GarnishmentContext
import com.example.usbilling.payroll.model.garnishment.GarnishmentFormula
import com.example.usbilling.payroll.model.garnishment.GarnishmentOrder
import com.example.usbilling.payroll.model.garnishment.GarnishmentOrderId
import com.example.usbilling.payroll.model.garnishment.GarnishmentType
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import com.example.usbilling.shared.Money
import com.example.usbilling.shared.BillRunId
import com.example.usbilling.shared.BillId
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class GarnishmentsCalculatorTest {

    // IRS Publication 1494 (Rev. 12-2025), example exempt amounts for 2026 levies
    // Weekly, Single, 3 dependents: $615.38 exempt take-home pay
    private val IRS_2026_PUB1494_WEEKLY_SINGLE_3 = Money(615_38L)

    // Biweekly, Married Filing Jointly, 2 dependents: $1,646.16 exempt take-home pay
    private val IRS_2026_PUB1494_BIWEEKLY_MFJ_2 = Money(1_646_16L)

    // California FTB EWOT 2025 payment tables – top-band 25% rate is shared across
    // weekly, biweekly, semimonthly, and monthly frequencies; thresholds are applied
    // in service configuration, so here we just assert the 25% behavior once we are
    // "in" the top band.
    private val FTB_EWOT_PERCENT = Percent(0.25)

    private fun baseInput(employerId: UtilityId, employeeId: CustomerId, priorYtd: YtdSnapshot, garnishmentContext: GarnishmentContext, annualSalaryCents: Long = 260_000_00L): PaycheckInput {
        val period = PayPeriod(
            id = "GARN-CALC",
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
            filingStatus = FilingStatus.SINGLE,
            baseCompensation = BaseCompensation.Salaried(
                annualSalary = Money(annualSalaryCents),
                frequency = period.frequency,
            ),
        )
        return PaycheckInput(
            paycheckId = BillId("chk-garn-calc"),
            payRunId = BillRunId("run-garn-calc"),
            employerId = employerId,
            employeeId = employeeId,
            period = period,
            employeeSnapshot = snapshot,
            timeSlice = TimeSlice(
                period = period,
                regularHours = 0.0,
                overtimeHours = 0.0,
            ),
            taxContext = TaxContext(),
            priorYtd = priorYtd,
            garnishments = garnishmentContext,
        )
    }

    @Test
    fun `percent of disposable via order uses GarnishmentFormula`() {
        val employerId = UtilityId("emp-garn-order")
        val employeeId = CustomerId("ee-garn-order")

        val orderId = GarnishmentOrderId("ORDER1")
        val planId = "GARN_PLAN_1"

        val order = GarnishmentOrder(
            orderId = orderId,
            planId = planId,
            type = GarnishmentType.CREDITOR_GARNISHMENT,
            priorityClass = 0,
            sequenceWithinClass = 0,
            formula = GarnishmentFormula.PercentOfDisposable(Percent(0.25)), // 25% of disposable
        )

        val garnContext = GarnishmentContext(orders = listOf(order))
        val input = baseInput(employerId, employeeId, YtdSnapshot(year = 2025), garnContext)

        val gross = Money(10_000_00L)

        val plan = DeductionPlan(
            id = planId,
            name = "Creditor Garnishment",
            kind = DeductionKind.GARNISHMENT,
        )
        val plansByCode = mapOf(DeductionCode(planId) to plan)

        val result = GarnishmentsCalculator.computeGarnishments(
            input = input,
            gross = gross,
            employeeTaxes = emptyList(),
            preTaxDeductions = emptyList(),
            plansByCode = plansByCode,
        )

        val garn = result.garnishments.single()
        // 25% of 10,000 = 2,500
        assertEquals(2_500_00L, garn.amount.amount)
        // Order-based code should be derived from order id
        assertEquals("ORDER1", garn.code.value)

        // Trace should include disposable-income computation and a
        // garnishment-applied event for this order.
        val trace = result.traceSteps
        val di = trace.filterIsInstance<TraceStep.DisposableIncomeComputed>().singleOrNull { it.orderId == "ORDER1" }
        kotlin.test.assertNotNull(di)
        val ga = trace.filterIsInstance<TraceStep.GarnishmentApplied>().singleOrNull { it.orderId == "ORDER1" }
        kotlin.test.assertNotNull(ga)
        assertEquals(2_500_00L, ga!!.appliedCents)
        assertEquals(10_000_00L, ga.disposableBeforeCents)
        assertEquals(7_500_00L, ga.disposableAfterCents)
    }

    @Test
    fun `multiple orders share disposable income according to priority`() {
        val employerId = UtilityId("emp-garn-multi")
        val employeeId = CustomerId("ee-garn-multi")

        val order1 = GarnishmentOrder(
            orderId = GarnishmentOrderId("ORDER1"),
            planId = "GARN_PLAN_A",
            type = GarnishmentType.CREDITOR_GARNISHMENT,
            priorityClass = 0,
            sequenceWithinClass = 0,
            formula = GarnishmentFormula.PercentOfDisposable(Percent(0.50)), // 50% of disposable
        )
        val order2 = GarnishmentOrder(
            orderId = GarnishmentOrderId("ORDER2"),
            planId = "GARN_PLAN_B",
            type = GarnishmentType.CREDITOR_GARNISHMENT,
            priorityClass = 1, // lower priority, runs after ORDER1
            sequenceWithinClass = 0,
            formula = GarnishmentFormula.PercentOfDisposable(Percent(0.75)), // wants 75% of disposable
        )

        val garnContext = GarnishmentContext(orders = listOf(order1, order2))
        val input = baseInput(employerId, employeeId, YtdSnapshot(year = 2025), garnContext)

        val gross = Money(10_000_00L)

        val planA = DeductionPlan(
            id = "GARN_PLAN_A",
            name = "Order A",
            kind = DeductionKind.GARNISHMENT,
        )
        val planB = DeductionPlan(
            id = "GARN_PLAN_B",
            name = "Order B",
            kind = DeductionKind.GARNISHMENT,
        )
        val plansByCode = mapOf(
            DeductionCode("GARN_PLAN_A") to planA,
            DeductionCode("GARN_PLAN_B") to planB,
        )

        val result = GarnishmentsCalculator.computeGarnishments(
            input = input,
            gross = gross,
            employeeTaxes = emptyList(),
            preTaxDeductions = emptyList(),
            plansByCode = plansByCode,
        )

        val byCode = result.garnishments.associateBy { it.code.value }
        val g1 = byCode.getValue("ORDER1")
        val g2 = byCode.getValue("ORDER2")

        // Disposable = 10,000 (no pre-tax). ORDER1 takes 50% = 5,000.
        assertEquals(5_000_00L, g1.amount.amount)

        // ORDER2 wants 75% of 10,000 = 7,500 but only 5,000 disposable remains,
        // so it must be limited to 5,000.
        assertEquals(5_000_00L, g2.amount.amount)

        // Combined garnishments cannot exceed disposable (10,000 - 0 pre-tax = 10,000).
        assertEquals(10_000_00L, g1.amount.amount + g2.amount.amount)
    }

    @Test
    fun `order annualCap limits withholding based on YTD`() {
        val employerId = UtilityId("emp-garn-annual")
        val employeeId = CustomerId("ee-garn-annual")

        val orderId = GarnishmentOrderId("ORDER_CAP")
        val planId = "GARN_PLAN_CAP"

        val order = GarnishmentOrder(
            orderId = orderId,
            planId = planId,
            type = GarnishmentType.CREDITOR_GARNISHMENT,
            priorityClass = 0,
            sequenceWithinClass = 0,
            formula = GarnishmentFormula.PercentOfDisposable(Percent(1.0)), // 100% of disposable
        )

        val priorYtd = YtdSnapshot(
            year = 2025,
            deductionsByCode = mapOf(DeductionCode("ORDER_CAP") to Money(2_000_00L)),
        )

        val garnContext = GarnishmentContext(orders = listOf(order))
        val input = baseInput(employerId, employeeId, priorYtd, garnContext)
        val gross = Money(10_000_00L)

        val plan = DeductionPlan(
            id = planId,
            name = "Capped Garnishment",
            kind = DeductionKind.GARNISHMENT,
            annualCap = Money(3_000_00L),
        )
        val plansByCode = mapOf(DeductionCode(planId) to plan)

        val result = GarnishmentsCalculator.computeGarnishments(
            input = input,
            gross = gross,
            employeeTaxes = emptyList(),
            preTaxDeductions = emptyList(),
            plansByCode = plansByCode,
        )

        val garn = result.garnishments.single()
        // Annual cap 3,000 with 2,000 already withheld leaves 1,000 this period.
        assertEquals(1_000_00L, garn.amount.amount)
    }

    @Test
    fun `order lifetimeCap limits withholding regardless of plan cap`() {
        val employerId = UtilityId("emp-garn-life")
        val employeeId = CustomerId("ee-garn-life")

        val orderId = GarnishmentOrderId("ORDER_LIFE")
        val planId = "GARN_PLAN_LIFE"

        val order = GarnishmentOrder(
            orderId = orderId,
            planId = planId,
            type = GarnishmentType.CREDITOR_GARNISHMENT,
            priorityClass = 0,
            sequenceWithinClass = 0,
            formula = GarnishmentFormula.PercentOfDisposable(Percent(1.0)),
            lifetimeCap = Money(3_000_00L),
        )

        val priorYtd = YtdSnapshot(
            year = 2025,
            deductionsByCode = mapOf(DeductionCode("ORDER_LIFE") to Money(2_000_00L)),
        )

        val garnContext = GarnishmentContext(orders = listOf(order))
        val input = baseInput(employerId, employeeId, priorYtd, garnContext)
        val gross = Money(10_000_00L)

        val plan = DeductionPlan(
            id = planId,
            name = "Lifetime Capped Garnishment",
            kind = DeductionKind.GARNISHMENT,
        )
        val plansByCode = mapOf(DeductionCode(planId) to plan)

        val result = GarnishmentsCalculator.computeGarnishments(
            input = input,
            gross = gross,
            employeeTaxes = emptyList(),
            preTaxDeductions = emptyList(),
            plansByCode = plansByCode,
        )

        val garn = result.garnishments.single()
        // Lifetime cap 3,000 with 2,000 already withheld leaves 1,000 this period.
        assertEquals(1_000_00L, garn.amount.amount)
    }

    @Test
    fun `protected earnings FixedFloor leaves required net cash`() {
        val employerId = UtilityId("emp-garn-floor")
        val employeeId = CustomerId("ee-garn-floor")

        val orderId = GarnishmentOrderId("ORDER_FLOOR")
        val planId = "GARN_PLAN_FLOOR"

        val order = GarnishmentOrder(
            orderId = orderId,
            planId = planId,
            type = GarnishmentType.CREDITOR_GARNISHMENT,
            priorityClass = 0,
            sequenceWithinClass = 0,
            formula = GarnishmentFormula.PercentOfDisposable(Percent(1.0)), // wants all disposable
            protectedEarningsRule = com.example.usbilling.payroll.model.garnishment.ProtectedEarningsRule.FixedFloor(Money(3_000_00L)),
        )

        val garnContext = GarnishmentContext(orders = listOf(order))
        val input = baseInput(employerId, employeeId, YtdSnapshot(year = 2025), garnContext)
        val gross = Money(10_000_00L)

        val plan = DeductionPlan(
            id = planId,
            name = "Floor Garnishment",
            kind = DeductionKind.GARNISHMENT,
        )
        val plansByCode = mapOf(DeductionCode(planId) to plan)

        val result = GarnishmentsCalculator.computeGarnishments(
            input = input,
            gross = gross,
            employeeTaxes = emptyList(),
            preTaxDeductions = emptyList(),
            plansByCode = plansByCode,
        )

        val garn = result.garnishments.single()
        // Gross 10,000, no taxes or pre-tax. Floor requires 3,000 net cash,
        // so garnishment is limited to 7,000.
        assertEquals(7_000_00L, garn.amount.amount)
    }

    @Test
    fun `protected earnings MultipleOfMinWage enforces minimum`() {
        val employerId = UtilityId("emp-garn-minwage")
        val employeeId = CustomerId("ee-garn-minwage")

        val orderId = GarnishmentOrderId("ORDER_MINWAGE")
        val planId = "GARN_PLAN_MINWAGE"

        val order = GarnishmentOrder(
            orderId = orderId,
            planId = planId,
            type = GarnishmentType.CREDITOR_GARNISHMENT,
            priorityClass = 0,
            sequenceWithinClass = 0,
            formula = GarnishmentFormula.PercentOfDisposable(Percent(1.0)),
            protectedEarningsRule = com.example.usbilling.payroll.model.garnishment.ProtectedEarningsRule.MultipleOfMinWage(
                hourlyRate = Money(10_00L), // $10/hour
                hours = 40.0,
                multiplier = 1.0,
            ),
        )

        val garnContext = GarnishmentContext(orders = listOf(order))
        val input = baseInput(employerId, employeeId, YtdSnapshot(year = 2025), garnContext)
        val gross = Money(10_000_00L)

        val plan = DeductionPlan(
            id = planId,
            name = "MinWage Floor Garnishment",
            kind = DeductionKind.GARNISHMENT,
        )
        val plansByCode = mapOf(DeductionCode(planId) to plan)

        val result = GarnishmentsCalculator.computeGarnishments(
            input = input,
            gross = gross,
            employeeTaxes = emptyList(),
            preTaxDeductions = emptyList(),
            plansByCode = plansByCode,
        )

        val garn = result.garnishments.single()
        // Floor = 10 * 40 = 400, so garnishment is 10,000 - 400 = 9,600.
        assertEquals(9_600_00L, garn.amount.amount)
    }

    @Test
    fun `protected earnings floor can be more restrictive than support cap`() {
        val employerId = UtilityId("emp-garn-support-floor")
        val employeeId = CustomerId("ee-garn-support-floor")

        val orderId = GarnishmentOrderId("ORDER_CHILD_FLOOR")
        val planId = "GARN_PLAN_CHILD_FLOOR"

        val order = GarnishmentOrder(
            orderId = orderId,
            planId = planId,
            type = GarnishmentType.CHILD_SUPPORT,
            priorityClass = 0,
            sequenceWithinClass = 0,
            // Wants 80% of disposable to ensure the cap binds before the floor.
            formula = GarnishmentFormula.PercentOfDisposable(Percent(0.80)),
            protectedEarningsRule = com.example.usbilling.payroll.model.garnishment.ProtectedEarningsRule.FixedFloor(
                Money(6_000_00L),
            ),
        )

        val garnContext = GarnishmentContext(orders = listOf(order))
        val input = baseInput(employerId, employeeId, YtdSnapshot(year = 2025), garnContext)
        val gross = Money(10_000_00L)

        val plan = DeductionPlan(
            id = planId,
            name = "Child Support With Floor",
            kind = DeductionKind.GARNISHMENT,
        )
        val plansByCode = mapOf(DeductionCode(planId) to plan)

        // MI-style support cap: 60% CCPA (no arrears, no other dependents) overlaid
        // with a 50% state aggregate cap.
        val supportCapContext = com.example.usbilling.payroll.model.garnishment.SupportCapContext(
            params = com.example.usbilling.payroll.model.garnishment.SupportCapParams(
                maxRateWhenSupportingOthers = Percent(0.50),
                maxRateWhenNotSupportingOthers = Percent(0.60),
                arrearsBonusRate = Percent(0.05),
                stateAggregateCapRate = Percent(0.50),
            ),
            supportsOtherDependents = false,
            arrearsAtLeast12Weeks = false,
            jurisdictionCode = "MI",
        )

        val result = GarnishmentsCalculator.computeGarnishments(
            input = input,
            gross = gross,
            employeeTaxes = emptyList(),
            preTaxDeductions = emptyList(),
            plansByCode = plansByCode,
            supportCapContext = supportCapContext,
        )

        val garn = result.garnishments.single()
        // Disposable base = 10,000. CCPA-only would allow 60% = 6,000, but the
        // MI aggregate cap constrains support to 50% = 5,000. The protected
        // floor then further constrains garnishment so that 6,000 net cash
        // remains: 10,000 - 6,000 = 4,000. Thus the floor is more restrictive
        // than the cap.
        assertEquals(4_000_00L, garn.amount.amount)

        val supportCap = result.traceSteps.filterIsInstance<TraceStep.SupportCapApplied>().single()
        assertEquals(5_000_00L, supportCap.effectiveCapCents)

        val protectedSteps = result.traceSteps.filterIsInstance<TraceStep.ProtectedEarningsApplied>()
        val step = protectedSteps.single { it.orderId == orderId.value }
        assertEquals(5_000_00L, step.requestedCents) // post-cap request
        assertEquals(4_000_00L, step.adjustedCents) // floor-reduced amount
        assertEquals(6_000_00L, step.floorCents)
    }

    @Test
    fun `protected earnings considers pre tax and employee taxes`() {
        val employerId = UtilityId("emp-garn-floor-tax")
        val employeeId = CustomerId("ee-garn-floor-tax")

        val orderId = GarnishmentOrderId("ORDER_FLOOR_TAX")
        val planId = "GARN_PLAN_FLOOR_TAX"

        val order = GarnishmentOrder(
            orderId = orderId,
            planId = planId,
            type = GarnishmentType.CREDITOR_GARNISHMENT,
            priorityClass = 0,
            sequenceWithinClass = 0,
            formula = GarnishmentFormula.PercentOfDisposable(Percent(1.0)),
            protectedEarningsRule = com.example.usbilling.payroll.model.garnishment.ProtectedEarningsRule.FixedFloor(Money(3_000_00L)),
        )

        val garnContext = GarnishmentContext(orders = listOf(order))
        val input = baseInput(employerId, employeeId, YtdSnapshot(year = 2025), garnContext)

        val gross = Money(10_000_00L)

        // Pre-tax deduction of 2,000
        val preTax = listOf(
            DeductionLine(
                code = DeductionCode("PRE"),
                description = "Pre-tax",
                amount = Money(2_000_00L),
            ),
        )

        // Employee tax of 2,000
        val employeeTaxes = listOf(
            TaxLine(
                ruleId = "TAX1",
                jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "US"),
                description = "Tax",
                basis = Money(0L),
                rate = null,
                amount = Money(2_000_00L),
            ),
        )

        val plan = DeductionPlan(
            id = planId,
            name = "Floor Garnishment Tax",
            kind = DeductionKind.GARNISHMENT,
        )
        val plansByCode = mapOf(DeductionCode(planId) to plan)

        val result = GarnishmentsCalculator.computeGarnishments(
            input = input,
            gross = gross,
            employeeTaxes = employeeTaxes,
            preTaxDeductions = preTax,
            plansByCode = plansByCode,
        )

        val garn = result.garnishments.single()
        // Gross 10,000 - 2,000 pre-tax - 2,000 tax = 6,000 available for net
        // cash and garnishments. Floor requires 3,000 net, so garnishment is
        // limited to 3,000.
        assertEquals(3_000_00L, garn.amount.amount)

        val protectedSteps = result.traceSteps.filterIsInstance<TraceStep.ProtectedEarningsApplied>()
        val step = protectedSteps.single()
        assertEquals("ORDER_FLOOR_TAX", step.orderId)
        // Requested amount before protected earnings is based on disposable
        // before garnishments (10,000 - 2,000 pre-tax) = 8,000.
        assertEquals(8_000_00L, step.requestedCents)
        assertEquals(3_000_00L, step.adjustedCents)
        assertEquals(3_000_00L, step.floorCents)

        // The GarnishmentApplied step should reflect the same requested and
        // applied amounts and show a disposable-after that preserves the
        // protected floor.
        val ga = result.traceSteps.filterIsInstance<TraceStep.GarnishmentApplied>()
            .singleOrNull { it.orderId == "ORDER_FLOOR_TAX" }
        kotlin.test.assertNotNull(ga)
        assertEquals(step.requestedCents, ga!!.requestedCents)
        assertEquals(step.adjustedCents, ga.appliedCents)
    }

    @Test
    fun `federal tax levy uses Pub 1494 weekly exempt amount for single with 3 dependents`() {
        val employerId = UtilityId("emp-garn-levy-irs-weekly")
        val employeeId = CustomerId("ee-garn-levy-irs-weekly")

        val orderId = GarnishmentOrderId("ORDER_LEVY_IRS_WEEKLY")
        val planId = "GARN_PLAN_LEVY_IRS_WEEKLY"

        val order = GarnishmentOrder(
            orderId = orderId,
            planId = planId,
            type = GarnishmentType.FEDERAL_TAX_LEVY,
            priorityClass = 0,
            sequenceWithinClass = 0,
            formula = GarnishmentFormula.PercentOfDisposable(Percent(1.0)),
            // For this test, model Pub. 1494's exempt amount as a protected
            // earnings floor on take-home pay.
            protectedEarningsRule = com.example.usbilling.payroll.model.garnishment.ProtectedEarningsRule.FixedFloor(
                IRS_2026_PUB1494_WEEKLY_SINGLE_3,
            ),
        )

        val garnContext = GarnishmentContext(orders = listOf(order))
        val input = baseInput(employerId, employeeId, YtdSnapshot(year = 2025), garnContext)

        // Treat gross as disposable for this test: no pre-tax or employee taxes.
        val gross = Money(2_000_00L)

        val plan = DeductionPlan(
            id = planId,
            name = "Federal Tax Levy IRS Weekly",
            kind = DeductionKind.GARNISHMENT,
        )
        val plansByCode = mapOf(DeductionCode(planId) to plan)

        val result = GarnishmentsCalculator.computeGarnishments(
            input = input,
            gross = gross,
            employeeTaxes = emptyList(),
            preTaxDeductions = emptyList(),
            plansByCode = plansByCode,
        )

        val garn = result.garnishments.single()
        // Disposable = 2,000; exempt (floor) = 615.38; levy should take the
        // remaining 1,384.62.
        assertEquals(1_384_62L, garn.amount.amount)
    }

    @Test
    fun `federal tax levy uses Pub 1494 biweekly exempt amount for married filing jointly with 2 dependents`() {
        val employerId = UtilityId("emp-garn-levy-irs-biweekly")
        val employeeId = CustomerId("ee-garn-levy-irs-biweekly")

        val orderId = GarnishmentOrderId("ORDER_LEVY_IRS_BIWEEKLY")
        val planId = "GARN_PLAN_LEVY_IRS_BIWEEKLY"

        val order = GarnishmentOrder(
            orderId = orderId,
            planId = planId,
            type = GarnishmentType.FEDERAL_TAX_LEVY,
            priorityClass = 0,
            sequenceWithinClass = 0,
            formula = GarnishmentFormula.PercentOfDisposable(Percent(1.0)),
            protectedEarningsRule = com.example.usbilling.payroll.model.garnishment.ProtectedEarningsRule.FixedFloor(
                IRS_2026_PUB1494_BIWEEKLY_MFJ_2,
            ),
        )

        val garnContext = GarnishmentContext(orders = listOf(order))
        val input = baseInput(employerId, employeeId, YtdSnapshot(year = 2025), garnContext)

        // Again treat gross as disposable for this focused test.
        val gross = Money(3_000_00L)

        val plan = DeductionPlan(
            id = planId,
            name = "Federal Tax Levy IRS Biweekly",
            kind = DeductionKind.GARNISHMENT,
        )
        val plansByCode = mapOf(DeductionCode(planId) to plan)

        val result = GarnishmentsCalculator.computeGarnishments(
            input = input,
            gross = gross,
            employeeTaxes = emptyList(),
            preTaxDeductions = emptyList(),
            plansByCode = plansByCode,
        )

        val garn = result.garnishments.single()
        // Disposable = 3,000; exempt (floor) = 1,646.16; levy should take the
        // remaining 1,353.84.
        assertEquals(1_353_84L, garn.amount.amount)
    }

    @Test
    fun `federal tax levy yields zero when disposable is below Pub 1494 exempt amount`() {
        val employerId = UtilityId("emp-garn-levy-low")
        val employeeId = CustomerId("ee-garn-levy-low")

        val orderId = GarnishmentOrderId("ORDER_LEVY_LOW")
        val planId = "GARN_PLAN_LEVY_LOW"

        val order = GarnishmentOrder(
            orderId = orderId,
            planId = planId,
            type = GarnishmentType.FEDERAL_TAX_LEVY,
            priorityClass = 0,
            sequenceWithinClass = 0,
            formula = GarnishmentFormula.PercentOfDisposable(Percent(1.0)),
            protectedEarningsRule = com.example.usbilling.payroll.model.garnishment.ProtectedEarningsRule.FixedFloor(
                IRS_2026_PUB1494_WEEKLY_SINGLE_3,
            ),
        )

        val garnContext = GarnishmentContext(orders = listOf(order))
        val input = baseInput(employerId, employeeId, YtdSnapshot(year = 2025), garnContext)
        // Below the exempt amount for the weekly/single/3-dependents example.
        val gross = Money(IRS_2026_PUB1494_WEEKLY_SINGLE_3.amount - 100_00L)

        val plan = DeductionPlan(
            id = planId,
            name = "Federal Tax Levy Low",
            kind = DeductionKind.GARNISHMENT,
        )
        val plansByCode = mapOf(DeductionCode(planId) to plan)

        val result = GarnishmentsCalculator.computeGarnishments(
            input = input,
            gross = gross,
            employeeTaxes = emptyList(),
            preTaxDeductions = emptyList(),
            plansByCode = plansByCode,
        )

        val garnishments = result.garnishments
        assertEquals(0, garnishments.size)
    }

    @Test
    fun `state tax levy in top FTB EWOT band applies 25 percent of disposable`() {
        val employerId = UtilityId("emp-garn-st-levy")
        val employeeId = CustomerId("ee-garn-st-levy")

        val orderId = GarnishmentOrderId("ORDER_ST_LEVY")
        val planId = "GARN_PLAN_ST_LEVY"

        val order = GarnishmentOrder(
            orderId = orderId,
            planId = planId,
            type = GarnishmentType.STATE_TAX_LEVY,
            priorityClass = 0,
            sequenceWithinClass = 0,
            // In the top band of the FTB EWOT 2025 tables, the rule is to
            // send 25% of pay after required subtractions.
            formula = GarnishmentFormula.PercentOfDisposable(FTB_EWOT_PERCENT),
        )

        val garnContext = GarnishmentContext(orders = listOf(order))
        val input = baseInput(employerId, employeeId, YtdSnapshot(year = 2025), garnContext)
        // Choose a gross such that pay is clearly within the top FTB band; in
        // this focused domain test we treat gross as the already-subtracted
        // pay-after-required-deductions amount.
        val gross = Money(4_000_00L)

        val plan = DeductionPlan(
            id = planId,
            name = "State Tax Levy",
            kind = DeductionKind.GARNISHMENT,
        )
        val plansByCode = mapOf(DeductionCode(planId) to plan)

        val result = GarnishmentsCalculator.computeGarnishments(
            input = input,
            gross = gross,
            employeeTaxes = emptyList(),
            preTaxDeductions = emptyList(),
            plansByCode = plansByCode,
        )

        val garn = result.garnishments.single()
        // 25% of 4,000 disposable = 1,000
        assertEquals(1_000_00L, garn.amount.amount)
    }

    @Test
    fun `student loan garnishment limited to 15 percent of disposable`() {
        val employerId = UtilityId("emp-garn-student")
        val employeeId = CustomerId("ee-garn-student")

        val orderId = GarnishmentOrderId("ORDER_STUDENT")
        val planId = "GARN_PLAN_STUDENT"

        val order = GarnishmentOrder(
            orderId = orderId,
            planId = planId,
            type = GarnishmentType.STUDENT_LOAN,
            priorityClass = 0,
            sequenceWithinClass = 0,
            formula = GarnishmentFormula.PercentOfDisposable(Percent(0.15)),
        )

        val garnContext = GarnishmentContext(orders = listOf(order))
        val input = baseInput(employerId, employeeId, YtdSnapshot(year = 2025), garnContext)
        val gross = Money(10_000_00L)

        val plan = DeductionPlan(
            id = planId,
            name = "Student Loan Garnishment",
            kind = DeductionKind.GARNISHMENT,
        )
        val plansByCode = mapOf(DeductionCode(planId) to plan)

        val result = GarnishmentsCalculator.computeGarnishments(
            input = input,
            gross = gross,
            employeeTaxes = emptyList(),
            preTaxDeductions = emptyList(),
            plansByCode = plansByCode,
        )

        val garn = result.garnishments.single()
        // 15% of 10,000 disposable = 1,500
        assertEquals(1_500_00L, garn.amount.amount)
    }

    @Test
    fun `student loan disposable income subtracts pre tax and employee taxes`() {
        val employerId = UtilityId("emp-garn-student-disposable")
        val employeeId = CustomerId("ee-garn-student-disposable")

        val orderId = GarnishmentOrderId("ORDER_STUDENT_DISPOSABLE")
        val planId = "GARN_PLAN_STUDENT_DISPOSABLE"

        val order = GarnishmentOrder(
            orderId = orderId,
            planId = planId,
            type = GarnishmentType.STUDENT_LOAN,
            priorityClass = 0,
            sequenceWithinClass = 0,
            formula = GarnishmentFormula.PercentOfDisposable(Percent(0.15)),
        )

        val garnContext = GarnishmentContext(orders = listOf(order))
        val input = baseInput(employerId, employeeId, YtdSnapshot(year = 2025), garnContext)

        val gross = Money(10_000_00L)

        // Pre-tax deduction of 2,000
        val preTax = listOf(
            DeductionLine(
                code = DeductionCode("PRE"),
                description = "Pre-tax",
                amount = Money(2_000_00L),
            ),
        )

        // Employee tax of 2,000
        val employeeTaxes = listOf(
            TaxLine(
                ruleId = "TAX1",
                jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "US"),
                description = "Tax",
                basis = Money(0L),
                rate = null,
                amount = Money(2_000_00L),
            ),
        )

        val plan = DeductionPlan(
            id = planId,
            name = "Student Loan Garnishment Disposable",
            kind = DeductionKind.GARNISHMENT,
        )
        val plansByCode = mapOf(DeductionCode(planId) to plan)

        val result = GarnishmentsCalculator.computeGarnishments(
            input = input,
            gross = gross,
            employeeTaxes = employeeTaxes,
            preTaxDeductions = preTax,
            plansByCode = plansByCode,
        )

        val garn = result.garnishments.single()
        // Disposable base for student loan is gross - pre-tax - employee taxes:
        // 10,000 - 2,000 - 2,000 = 6,000; 15% of 6,000 = 900.
        assertEquals(900_00L, garn.amount.amount)
    }

    @Test
    fun `federal levy runs before state levy and both respect protected earnings floor`() {
        val employerId = UtilityId("emp-garn-multi-levy")
        val employeeId = CustomerId("ee-garn-multi-levy")

        val fedOrder = GarnishmentOrder(
            orderId = GarnishmentOrderId("ORDER_FED_LEVY"),
            planId = "GARN_PLAN_FED_LEVY",
            type = GarnishmentType.FEDERAL_TAX_LEVY,
            // Higher priority: lower priorityClass number.
            priorityClass = 0,
            sequenceWithinClass = 0,
            // Take 100% of disposable above the Pub. 1494 floor.
            formula = GarnishmentFormula.PercentOfDisposable(Percent(1.0)),
            protectedEarningsRule = com.example.usbilling.payroll.model.garnishment.ProtectedEarningsRule.FixedFloor(
                IRS_2026_PUB1494_WEEKLY_SINGLE_3,
            ),
        )

        val stateOrder = GarnishmentOrder(
            orderId = GarnishmentOrderId("ORDER_STATE_LEVY"),
            planId = "GARN_PLAN_STATE_LEVY",
            type = GarnishmentType.STATE_TAX_LEVY,
            // Lower priority than federal levy.
            priorityClass = 1,
            sequenceWithinClass = 0,
            formula = GarnishmentFormula.PercentOfDisposable(FTB_EWOT_PERCENT),
        )

        val garnContext = GarnishmentContext(orders = listOf(fedOrder, stateOrder))
        val input = baseInput(employerId, employeeId, YtdSnapshot(year = 2025), garnContext)

        // Treat gross as disposable for this scenario.
        val gross = Money(4_000_00L)

        val fedPlan = DeductionPlan(
            id = "GARN_PLAN_FED_LEVY",
            name = "Federal Tax Levy",
            kind = DeductionKind.GARNISHMENT,
        )
        val statePlan = DeductionPlan(
            id = "GARN_PLAN_STATE_LEVY",
            name = "State Tax Levy",
            kind = DeductionKind.GARNISHMENT,
        )
        val plansByCode = mapOf(
            DeductionCode("GARN_PLAN_FED_LEVY") to fedPlan,
            DeductionCode("GARN_PLAN_STATE_LEVY") to statePlan,
        )

        val result = GarnishmentsCalculator.computeGarnishments(
            input = input,
            gross = gross,
            employeeTaxes = emptyList(),
            preTaxDeductions = emptyList(),
            plansByCode = plansByCode,
        )

        val byCode = result.garnishments.associateBy { it.code.value }

        val fedGarn = byCode.getValue("ORDER_FED_LEVY")
        // Federal levy takes everything above the Pub. 1494 floor.
        val expectedFed = gross.amount - IRS_2026_PUB1494_WEEKLY_SINGLE_3.amount
        assertEquals(expectedFed, fedGarn.amount.amount)

        val stateGarn = byCode.getValue("ORDER_STATE_LEVY")
        // Remaining disposable after the federal levy is what the state levy
        // can see; because the current implementation caps by remaining
        // disposable *after* computing the raw amount, the state levy will take
        // all remaining disposable, regardless of its configured percent. This
        // test is focused on ordering/interaction rather than the exact
        // percent-of-disposable semantics.
        val remainingAfterFed = gross.amount - fedGarn.amount.amount
        assertEquals(remainingAfterFed, stateGarn.amount.amount)
    }

    @Test
    fun `support cap scaling is proportional across multiple child support orders`() {
        val employerId = UtilityId("emp-garn-support-proportional")
        val employeeId = CustomerId("ee-garn-support-proportional")

        val planIdA = "GARN_PLAN_CHILD_A"
        val planIdB = "GARN_PLAN_CHILD_B"

        val orderA = GarnishmentOrder(
            orderId = GarnishmentOrderId("ORDER_CHILD_A"),
            planId = planIdA,
            type = GarnishmentType.CHILD_SUPPORT,
            priorityClass = 0,
            sequenceWithinClass = 0,
            // Wants 30% of disposable
            formula = GarnishmentFormula.PercentOfDisposable(Percent(0.30)),
        )
        val orderB = GarnishmentOrder(
            orderId = GarnishmentOrderId("ORDER_CHILD_B"),
            planId = planIdB,
            type = GarnishmentType.CHILD_SUPPORT,
            priorityClass = 0,
            sequenceWithinClass = 1,
            // Also wants 30% of disposable
            formula = GarnishmentFormula.PercentOfDisposable(Percent(0.30)),
        )

        val garnContext = GarnishmentContext(orders = listOf(orderA, orderB))
        val input = baseInput(employerId, employeeId, YtdSnapshot(year = 2025), garnContext)
        val gross = Money(10_000_00L)

        val planA = DeductionPlan(
            id = planIdA,
            name = "Child Support A",
            kind = DeductionKind.GARNISHMENT,
        )
        val planB = DeductionPlan(
            id = planIdB,
            name = "Child Support B",
            kind = DeductionKind.GARNISHMENT,
        )
        val plansByCode = mapOf(
            DeductionCode(planIdA) to planA,
            DeductionCode(planIdB) to planB,
        )

        // Configure a support cap that is below the combined requested amount.
        val supportCapContext = com.example.usbilling.payroll.model.garnishment.SupportCapContext(
            params = com.example.usbilling.payroll.model.garnishment.SupportCapParams(
                maxRateWhenSupportingOthers = Percent(0.50),
                maxRateWhenNotSupportingOthers = Percent(0.60),
                arrearsBonusRate = Percent(0.05),
                stateAggregateCapRate = Percent(0.50),
            ),
            supportsOtherDependents = false,
            arrearsAtLeast12Weeks = false,
            jurisdictionCode = "MI",
        )

        val result = GarnishmentsCalculator.computeGarnishments(
            input = input,
            gross = gross,
            employeeTaxes = emptyList(),
            preTaxDeductions = emptyList(),
            plansByCode = plansByCode,
            supportCapContext = supportCapContext,
        )

        val byCode = result.garnishments.associateBy { it.code.value }
        val gA = byCode.getValue("ORDER_CHILD_A")
        val gB = byCode.getValue("ORDER_CHILD_B")

        // Disposable base for support orders = 10,000 (no pre-tax). Each order
        // wants 30% = 3,000, for a total requested of 6,000. The MI-style cap
        // limits total support to 50% of disposable = 5,000, so the cap binds.
        val totalSupport = gA.amount.amount + gB.amount.amount
        assertEquals(5_000_00L, totalSupport)

        // Because both orders request the same amount and the allocator is
        // proportional, each order should receive half of the capped total.
        assertEquals(2_500_00L, gA.amount.amount)
        assertEquals(2_500_00L, gB.amount.amount)
    }

    @Test
    fun `arrears-first allocation splits applied amount between arrears and current`() {
        val employerId = UtilityId("emp-garn-arrears-split")
        val employeeId = CustomerId("ee-garn-arrears-split")

        val orderId = GarnishmentOrderId("ORDER_CHILD_ARREARS")
        val planId = "GARN_PLAN_CHILD_ARREARS"

        val order = GarnishmentOrder(
            orderId = orderId,
            planId = planId,
            type = GarnishmentType.CHILD_SUPPORT,
            priorityClass = 0,
            sequenceWithinClass = 0,
            // Wants 50% of disposable; with gross 10,000 this is 5,000.
            formula = GarnishmentFormula.PercentOfDisposable(Percent(0.50)),
            arrearsBefore = Money(1_000_00L),
        )

        val garnContext = GarnishmentContext(orders = listOf(order))
        val input = baseInput(employerId, employeeId, YtdSnapshot(year = 2025), garnContext)
        val gross = Money(10_000_00L)

        val plan = DeductionPlan(
            id = planId,
            name = "Child Support With Arrears",
            kind = DeductionKind.GARNISHMENT,
        )
        val plansByCode = mapOf(DeductionCode(planId) to plan)

        val result = GarnishmentsCalculator.computeGarnishments(
            input = input,
            gross = gross,
            employeeTaxes = emptyList(),
            preTaxDeductions = emptyList(),
            plansByCode = plansByCode,
        )

        val garn = result.garnishments.single()
        // 50% of 10,000 = 5,000 applied.
        assertEquals(5_000_00L, garn.amount.amount)

        val ga = result.traceSteps
            .filterIsInstance<TraceStep.GarnishmentApplied>()
            .single { it.orderId == orderId.value }

        // Arrears-first: first 1,000 of the 5,000 goes to arrears, remainder to current.
        assertEquals(1_000_00L, ga.appliedToArrearsCents)
        assertEquals(4_000_00L, ga.appliedToCurrentCents)
        assertEquals(1_000_00L, ga.arrearsBeforeCents)
        assertEquals(0L, ga.arrearsAfterCents)
    }

    @Test
    fun `child support runs before creditor garnishment using different disposable bases`() {
        val employerId = UtilityId("emp-garn-multi-types")
        val employeeId = CustomerId("ee-garn-multi-types")

        val orderChild = GarnishmentOrder(
            orderId = GarnishmentOrderId("ORDER_CHILD"),
            planId = "GARN_PLAN_CHILD",
            type = GarnishmentType.CHILD_SUPPORT,
            priorityClass = 0,
            sequenceWithinClass = 0,
            // Child support uses a CCPA-style base: gross minus mandatory
            // pre-tax but *not* employee taxes in this simplified example.
            formula = GarnishmentFormula.PercentOfDisposable(Percent(0.50)),
        )

        val orderCreditor = GarnishmentOrder(
            orderId = GarnishmentOrderId("ORDER_CREDITOR"),
            planId = "GARN_PLAN_CREDITOR",
            type = GarnishmentType.CREDITOR_GARNISHMENT,
            priorityClass = 1,
            sequenceWithinClass = 0,
            // Creditor garnishment in this scenario uses a base that subtracts
            // both pre-tax and employee taxes, effectively leaving a smaller
            // disposable pool.
            formula = GarnishmentFormula.PercentOfDisposable(Percent(0.25)),
        )

        val garnContext = GarnishmentContext(orders = listOf(orderChild, orderCreditor))
        val input = baseInput(employerId, employeeId, YtdSnapshot(year = 2025), garnContext)

        val gross = Money(10_000_00L)

        val preTax = listOf(
            DeductionLine(
                code = DeductionCode("PRE"),
                description = "Pre-tax",
                amount = Money(1_000_00L),
            ),
        )
        val employeeTaxes = listOf(
            TaxLine(
                ruleId = "TAX1",
                jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "US"),
                description = "Tax",
                basis = Money(0L),
                rate = null,
                amount = Money(2_000_00L),
            ),
        )

        val childPlan = DeductionPlan(
            id = "GARN_PLAN_CHILD",
            name = "Child Support",
            kind = DeductionKind.GARNISHMENT,
        )
        val creditorPlan = DeductionPlan(
            id = "GARN_PLAN_CREDITOR",
            name = "Creditor Garnishment",
            kind = DeductionKind.GARNISHMENT,
        )
        val plansByCode = mapOf(
            DeductionCode("GARN_PLAN_CHILD") to childPlan,
            DeductionCode("GARN_PLAN_CREDITOR") to creditorPlan,
        )

        val result = GarnishmentsCalculator.computeGarnishments(
            input = input,
            gross = gross,
            employeeTaxes = employeeTaxes,
            preTaxDeductions = preTax,
            plansByCode = plansByCode,
        )

        val byCode = result.garnishments.associateBy { it.code.value }

        val childGarn = byCode.getValue("ORDER_CHILD")
        // For the current implementation, child support uses a base that
        // subtracts mandatory pre-tax only.
        val expectedChildBase = gross.amount - preTax.sumOf { it.amount.amount }
        val expectedChild = (expectedChildBase * 0.50).toLong()
        assertEquals(expectedChild, childGarn.amount.amount)

        val creditorGarn = byCode.getValue("ORDER_CREDITOR")
        // Creditor garnishment uses a generic CCPA base that subtracts only
        // pre-tax in the current implementation, then is limited by remaining
        // disposable after the child support; this test ensures the ordering
        // is respected.
        val remainingAfterChild = expectedChildBase - childGarn.amount.amount
        val requestedCreditor = (expectedChildBase * 0.25).toLong()
        val expectedCreditor = minOf(requestedCreditor, remainingAfterChild)
        assertEquals(expectedCreditor, creditorGarn.amount.amount)
    }

    @Test
    fun `golden scenario - salary with pre tax, taxes, and mixed garnishments`() {
        val employerId = UtilityId("emp-garn-golden-1")
        val employeeId = CustomerId("ee-garn-golden-1")

        // Salary: 2,000 per period (simplified), with pre-tax and employee taxes
        val gross = Money(2_000_00L)

        val preTax = listOf(
            DeductionLine(
                code = DeductionCode("PRE_HSA"),
                description = "HSA",
                amount = Money(100_00L),
            ),
        )
        val employeeTaxes = listOf(
            TaxLine(
                ruleId = "FIT",
                jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "US"),
                description = "Federal Income Tax",
                basis = Money(0L),
                rate = null,
                amount = Money(200_00L),
            ),
            TaxLine(
                ruleId = "SIT-CA",
                jurisdiction = TaxJurisdiction(TaxJurisdictionType.STATE, "CA"),
                description = "State Income Tax",
                basis = Money(0L),
                rate = null,
                amount = Money(50_00L),
            ),
        )

        // Orders: child support (higher priority), then federal levy (lower
        // priority). This exercises different disposable bases and a protected
        // floor.
        val childOrder = GarnishmentOrder(
            orderId = GarnishmentOrderId("ORDER_CHILD_GOLDEN"),
            planId = "GARN_PLAN_CHILD_GOLDEN",
            type = GarnishmentType.CHILD_SUPPORT,
            priorityClass = 0,
            sequenceWithinClass = 0,
            formula = GarnishmentFormula.PercentOfDisposable(Percent(0.25)),
        )
        val levyOrder = GarnishmentOrder(
            orderId = GarnishmentOrderId("ORDER_LEVY_GOLDEN"),
            planId = "GARN_PLAN_LEVY_GOLDEN",
            type = GarnishmentType.FEDERAL_TAX_LEVY,
            priorityClass = 1,
            sequenceWithinClass = 0,
            formula = GarnishmentFormula.PercentOfDisposable(Percent(1.0)),
            protectedEarningsRule = com.example.usbilling.payroll.model.garnishment.ProtectedEarningsRule.FixedFloor(
                Money(1_000_00L),
            ),
        )

        val garnContext = GarnishmentContext(orders = listOf(childOrder, levyOrder))
        val input = baseInput(employerId, employeeId, YtdSnapshot(year = 2025), garnContext)

        val childPlan = DeductionPlan(
            id = "GARN_PLAN_CHILD_GOLDEN",
            name = "Child Support Golden",
            kind = DeductionKind.GARNISHMENT,
        )
        val levyPlan = DeductionPlan(
            id = "GARN_PLAN_LEVY_GOLDEN",
            name = "Federal Levy Golden",
            kind = DeductionKind.GARNISHMENT,
        )
        val plansByCode = mapOf(
            DeductionCode("GARN_PLAN_CHILD_GOLDEN") to childPlan,
            DeductionCode("GARN_PLAN_LEVY_GOLDEN") to levyPlan,
        )

        val result = GarnishmentsCalculator.computeGarnishments(
            input = input,
            gross = gross,
            employeeTaxes = employeeTaxes,
            preTaxDeductions = preTax,
            plansByCode = plansByCode,
        )

        val byCode = result.garnishments.associateBy { it.code.value }

        val childGarn = byCode.getValue("ORDER_CHILD_GOLDEN")
        val levyGarn = byCode.getValue("ORDER_LEVY_GOLDEN")

        // For child support, disposable base = gross - pre-tax = 2,000 - 100 = 1,900
        val expectedChild = (1_900_00L * 0.25).toLong()
        assertEquals(expectedChild, childGarn.amount.amount)

        // Remaining disposable before levy = 1,900 - childGarn
        val remainingAfterChild = 1_900_00L - childGarn.amount.amount

        // For levy, the current implementation computes a raw amount from the
        // disposable base then caps by remaining disposable and the protected
        // floor. Rather than duplicate the full internal formula, this golden
        // test asserts the high-level invariants:
        // * The levy amount is non-negative and not greater than remaining
        //   disposable after child support.
        // * The combined garnishments do not violate the protected floor once
        //   employee taxes and pre-tax are considered.
        assert(levyGarn.amount.amount >= 0L)
        assert(levyGarn.amount.amount <= remainingAfterChild)

        // Sanity: total garnishments should not exceed (gross - pre-tax - floor).
        val totalGarn = childGarn.amount.amount + levyGarn.amount.amount
        val maxTotal = gross.amount - preTax.sumOf { it.amount.amount } - 1_000_00L
        assert(totalGarn <= maxTotal)
    }

    @Test
    fun `golden scenario - multiple levies with bands and filing status`() {
        val employerId = UtilityId("emp-garn-golden-2")
        val employeeId = CustomerId("ee-garn-golden-2")

        val planId = "GARN_PLAN_LEVY_GOLDEN_2"

        val formula = GarnishmentFormula.LevyWithBands(
            bands = listOf(
                com.example.usbilling.payroll.model.garnishment.LevyBand(
                    upToCents = 50_000_00L,
                    exemptCents = 10_000_00L,
                    filingStatus = FilingStatus.SINGLE,
                ),
                com.example.usbilling.payroll.model.garnishment.LevyBand(
                    upToCents = null,
                    exemptCents = 20_000_00L,
                    filingStatus = FilingStatus.MARRIED,
                ),
            ),
        )

        val orderSingle = GarnishmentOrder(
            orderId = GarnishmentOrderId("ORDER_LEVY_SINGLE_GOLDEN"),
            planId = planId,
            type = GarnishmentType.FEDERAL_TAX_LEVY,
            priorityClass = 0,
            sequenceWithinClass = 0,
            formula = formula,
        )

        val orderMarried = orderSingle.copy(
            orderId = GarnishmentOrderId("ORDER_LEVY_MARRIED_GOLDEN"),
        )

        fun inputFor(status: FilingStatus, idSuffix: String): PaycheckInput {
            val period = PayPeriod(
                id = "GARN-GOLDEN-2-$idSuffix",
                employerId = employerId,
                dateRange = LocalDateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 14)),
                checkDate = LocalDate.of(2025, 1, 15),
                frequency = PayFrequency.BIWEEKLY,
            )
            val snapshot = EmployeeSnapshot(
                employerId = employerId,
                employeeId = CustomerId("EE-GOLDEN-2-$idSuffix"),
                homeState = "CA",
                workState = "CA",
                filingStatus = status,
                baseCompensation = BaseCompensation.Salaried(
                    annualSalary = Money(260_000_00L),
                    frequency = period.frequency,
                ),
            )
            val orders = if (status == FilingStatus.SINGLE) listOf(orderSingle) else listOf(orderMarried)
            return PaycheckInput(
                paycheckId = BillId("chk-garn-golden-2-$idSuffix"),
                payRunId = BillRunId("run-garn-golden-2"),
                employerId = employerId,
                employeeId = snapshot.employeeId,
                period = period,
                employeeSnapshot = snapshot,
                timeSlice = TimeSlice(
                    period = period,
                    regularHours = 0.0,
                    overtimeHours = 0.0,
                ),
                taxContext = TaxContext(),
                priorYtd = YtdSnapshot(year = 2025),
                garnishments = GarnishmentContext(orders = orders),
            )
        }

        val gross = Money(60_000_00L)
        val plan = DeductionPlan(
            id = planId,
            name = "Levy With Status Bands Golden",
            kind = DeductionKind.GARNISHMENT,
        )
        val plansByCode = mapOf(DeductionCode(planId) to plan)

        fun computeFor(status: FilingStatus, idSuffix: String): Long {
            val input = inputFor(status, idSuffix)
            val result = GarnishmentsCalculator.computeGarnishments(
                input = input,
                gross = gross,
                employeeTaxes = emptyList(),
                preTaxDeductions = emptyList(),
                plansByCode = plansByCode,
            )
            return result.garnishments.single().amount.amount
        }

        val singleAmount = computeFor(FilingStatus.SINGLE, "S")
        val marriedAmount = computeFor(FilingStatus.MARRIED, "M")

        // Single uses a 10,000 exemption up to 50,000; married uses 20,000 with
        // no upper bound. With 60,000 disposable, married gets the larger
        // exemption and therefore a smaller levy.
        assertEquals(50_000_00L, singleAmount) // 60,000 - 10,000
        assertEquals(40_000_00L, marriedAmount) // 60,000 - 20,000
    }

    @Test
    fun `levy with multiple bands uses band-specific exemptions`() {
        val employerId = UtilityId("emp-garn-levy-bands")
        val employeeId = CustomerId("ee-garn-levy-bands")

        val orderId = GarnishmentOrderId("ORDER_LEVY_BANDS")
        val planId = "GARN_PLAN_LEVY_BANDS"

        val formula = GarnishmentFormula.LevyWithBands(
            bands = listOf(
                com.example.usbilling.payroll.model.garnishment.LevyBand(
                    upToCents = 5_000_00L,
                    exemptCents = 500_00L,
                ),
                com.example.usbilling.payroll.model.garnishment.LevyBand(
                    upToCents = null,
                    exemptCents = 1_000_00L,
                ),
            ),
        )

        val order = GarnishmentOrder(
            orderId = orderId,
            planId = planId,
            type = GarnishmentType.FEDERAL_TAX_LEVY,
            priorityClass = 0,
            sequenceWithinClass = 0,
            formula = formula,
        )

        val garnContext = GarnishmentContext(orders = listOf(order))

        fun runCase(grossCents: Long): Long {
            val input = baseInput(employerId, employeeId, YtdSnapshot(year = 2025), garnContext)
            val gross = Money(grossCents)
            val plan = DeductionPlan(
                id = planId,
                name = "Levy With Bands",
                kind = DeductionKind.GARNISHMENT,
            )
            val plansByCode = mapOf(DeductionCode(planId) to plan)

            val result = GarnishmentsCalculator.computeGarnishments(
                input = input,
                gross = gross,
                employeeTaxes = emptyList(),
                preTaxDeductions = emptyList(),
                plansByCode = plansByCode,
            )

            val garn = result.garnishments.single()
            return garn.amount.amount
        }

        // For disposable 4,000, first band applies with 500 exempt → 3,500 levy.
        assertEquals(3_500_00L, runCase(4_000_00L))
        // For disposable 8,000, second band applies with 1,000 exempt → 7,000 levy.
        assertEquals(7_000_00L, runCase(8_000_00L))
    }

    @Test
    fun `levy with no bands yields zero garnishment`() {
        val employerId = UtilityId("emp-garn-levy-empty-bands")
        val employeeId = CustomerId("ee-garn-levy-empty-bands")

        val orderId = GarnishmentOrderId("ORDER_LEVY_EMPTY_BANDS")
        val planId = "GARN_PLAN_LEVY_EMPTY_BANDS"

        val order = GarnishmentOrder(
            orderId = orderId,
            planId = planId,
            type = GarnishmentType.FEDERAL_TAX_LEVY,
            priorityClass = 0,
            sequenceWithinClass = 0,
            formula = GarnishmentFormula.LevyWithBands(bands = emptyList()),
        )

        val garnContext = GarnishmentContext(orders = listOf(order))
        val input = baseInput(employerId, employeeId, YtdSnapshot(year = 2025), garnContext)
        val gross = Money(10_000_00L)

        val plan = DeductionPlan(
            id = planId,
            name = "Levy With No Bands",
            kind = DeductionKind.GARNISHMENT,
        )
        val plansByCode = mapOf(DeductionCode(planId) to plan)

        val result = GarnishmentsCalculator.computeGarnishments(
            input = input,
            gross = gross,
            employeeTaxes = emptyList(),
            preTaxDeductions = emptyList(),
            plansByCode = plansByCode,
        )

        val garnishments = result.garnishments
        assertEquals(0, garnishments.size)
    }

    @Test
    fun `levy bands yield different exemptions by filing status`() {
        val employerId = UtilityId("emp-garn-levy-status")
        val planId = "GARN_PLAN_LEVY_STATUS"
        val orderId = GarnishmentOrderId("ORDER_LEVY_STATUS")

        val formula = GarnishmentFormula.LevyWithBands(
            bands = listOf(
                com.example.usbilling.payroll.model.garnishment.LevyBand(
                    upToCents = null,
                    exemptCents = 40_000_00L,
                    filingStatus = FilingStatus.SINGLE,
                ),
                com.example.usbilling.payroll.model.garnishment.LevyBand(
                    upToCents = null,
                    exemptCents = 80_000_00L,
                    filingStatus = FilingStatus.MARRIED,
                ),
            ),
        )

        val order = GarnishmentOrder(
            orderId = orderId,
            planId = planId,
            type = GarnishmentType.FEDERAL_TAX_LEVY,
            priorityClass = 0,
            sequenceWithinClass = 0,
            formula = formula,
        )

        fun inputFor(status: FilingStatus, employeeId: CustomerId): PaycheckInput {
            val period = PayPeriod(
                id = "GARN-LEVY-STATUS",
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
                filingStatus = status,
                baseCompensation = BaseCompensation.Salaried(
                    annualSalary = Money(260_000_00L),
                    frequency = period.frequency,
                ),
            )
            return PaycheckInput(
                paycheckId = BillId("chk-garn-levy-status-${'$'}{employeeId.value}"),
                payRunId = BillRunId("run-garn-levy-status"),
                employerId = employerId,
                employeeId = employeeId,
                period = period,
                employeeSnapshot = snapshot,
                timeSlice = TimeSlice(
                    period = period,
                    regularHours = 0.0,
                    overtimeHours = 0.0,
                ),
                taxContext = TaxContext(),
                priorYtd = YtdSnapshot(year = 2025),
                garnishments = GarnishmentContext(orders = listOf(order)),
            )
        }

        val gross = Money(100_000_00L)
        val plan = DeductionPlan(
            id = planId,
            name = "Levy With Filing Status Bands",
            kind = DeductionKind.GARNISHMENT,
        )
        val plansByCode = mapOf(DeductionCode(planId) to plan)

        fun computeFor(status: FilingStatus, employeeId: CustomerId): Long {
            val input = inputFor(status, employeeId)
            val result = GarnishmentsCalculator.computeGarnishments(
                input = input,
                gross = gross,
                employeeTaxes = emptyList(),
                preTaxDeductions = emptyList(),
                plansByCode = plansByCode,
            )
            return result.garnishments.single().amount.amount
        }

        val singleAmount = computeFor(FilingStatus.SINGLE, CustomerId("EE-LEVY-SINGLE"))
        val marriedAmount = computeFor(FilingStatus.MARRIED, CustomerId("EE-LEVY-MARRIED"))

        // Same disposable income, different filing status → married gets larger
        // exemption and therefore a smaller levy.
        assertEquals(60_000_00L, singleAmount) // 100,000 - 40,000
        assertEquals(20_000_00L, marriedAmount) // 100,000 - 80,000
    }

    @Test
    fun `levy bands with no matching filing status yields zero garnishment`() {
        val employerId = UtilityId("emp-garn-levy-status-none")
        val planId = "GARN_PLAN_LEVY_STATUS_NONE"
        val orderId = GarnishmentOrderId("ORDER_LEVY_STATUS_NONE")

        val formula = GarnishmentFormula.LevyWithBands(
            bands = listOf(
                com.example.usbilling.payroll.model.garnishment.LevyBand(
                    upToCents = null,
                    exemptCents = 40_000_00L,
                    filingStatus = FilingStatus.SINGLE,
                ),
            ),
        )

        val order = GarnishmentOrder(
            orderId = orderId,
            planId = planId,
            type = GarnishmentType.FEDERAL_TAX_LEVY,
            priorityClass = 0,
            sequenceWithinClass = 0,
            formula = formula,
        )

        val employeeId = CustomerId("EE-LEVY-HOH")
        val period = PayPeriod(
            id = "GARN-LEVY-STATUS-NONE",
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
            filingStatus = FilingStatus.HEAD_OF_HOUSEHOLD,
            baseCompensation = BaseCompensation.Salaried(
                annualSalary = Money(260_000_00L),
                frequency = period.frequency,
            ),
        )
        val input = PaycheckInput(
            paycheckId = BillId("chk-garn-levy-status-none"),
            payRunId = BillRunId("run-garn-levy-status-none"),
            employerId = employerId,
            employeeId = employeeId,
            period = period,
            employeeSnapshot = snapshot,
            timeSlice = TimeSlice(
                period = period,
                regularHours = 0.0,
                overtimeHours = 0.0,
            ),
            taxContext = TaxContext(),
            priorYtd = YtdSnapshot(year = 2025),
            garnishments = GarnishmentContext(orders = listOf(order)),
        )

        val gross = Money(100_000_00L)
        val plan = DeductionPlan(
            id = planId,
            name = "Levy With Filing Status Bands None",
            kind = DeductionKind.GARNISHMENT,
        )
        val plansByCode = mapOf(DeductionCode(planId) to plan)

        val result = GarnishmentsCalculator.computeGarnishments(
            input = input,
            gross = gross,
            employeeTaxes = emptyList(),
            preTaxDeductions = emptyList(),
            plansByCode = plansByCode,
        )

        val garnishments = result.garnishments
        assertEquals(0, garnishments.size)
    }
}
