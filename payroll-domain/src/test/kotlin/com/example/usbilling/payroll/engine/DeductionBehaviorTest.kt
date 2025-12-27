package com.example.usbilling.payroll.engine

import com.example.usbilling.payroll.model.*
import com.example.usbilling.payroll.model.config.DeductionConfigRepository
import com.example.usbilling.payroll.model.config.DeductionKind
import com.example.usbilling.payroll.model.config.DeductionPlan
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import com.example.usbilling.shared.Money
import com.example.usbilling.shared.BillRunId
import com.example.usbilling.shared.BillId
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class DeductionBehaviorTest {

    private fun baseInput(employerId: UtilityId, employeeId: CustomerId, priorYtd: YtdSnapshot, annualSalaryCents: Long = 260_000_00L): PaycheckInput {
        val period = PayPeriod(
            id = "DED-BEHAVIOR",
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
            paycheckId = BillId("chk-ded"),
            payRunId = BillRunId("run-ded"),
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
        )
    }

    @Test
    fun `HSA contribution is capped by annual limit`() {
        val employerId = UtilityId("emp-hsa")
        val employeeId = CustomerId("ee-hsa")

        val planId = "HSA_PLAN"
        val annualCap = Money(3_000_00L)

        val repo = object : DeductionConfigRepository {
            override fun findPlansForEmployer(employerId: UtilityId): List<DeductionPlan> = listOf(
                DeductionPlan(
                    id = planId,
                    name = "HSA",
                    kind = DeductionKind.HSA,
                    employeeRate = Percent(0.20), // 20% of gross (10,000) = 2,000 per check
                    annualCap = annualCap,
                ),
            )
        }

        // First check, YTD = 0 -> full 2,000 allowed
        val input1 = baseInput(employerId, employeeId, YtdSnapshot(year = 2025))
        val result1 = calculatePaycheckDebug(
            input = input1,
            earningConfig = null,
            deductionConfig = repo,
        )
        val hsa1 = result1.deductions.single()
        assertEquals(2_000_00L, hsa1.amount.amount)

        // Trace: HSA should have DeductionApplied with HSA effects (including FICA reduction flags)
        val hsaStep1 = result1.trace.steps.filterIsInstance<TraceStep.DeductionApplied>()
            .first { it.description == "HSA" }
        val effects1 = hsaStep1.effects
        // HSA default effects include FICA bases
        assert(effects1.contains(com.example.usbilling.payroll.model.config.DeductionEffect.REDUCES_FEDERAL_TAXABLE))
        assert(effects1.contains(com.example.usbilling.payroll.model.config.DeductionEffect.REDUCES_SOCIAL_SECURITY_WAGES))
        assert(effects1.contains(com.example.usbilling.payroll.model.config.DeductionEffect.REDUCES_MEDICARE_WAGES))

        // Second check, prior YTD already 2,000 -> only 1,000 remaining to hit 3,000 cap
        val priorYtd2 = result1.ytdAfter
        val input2 = baseInput(employerId, employeeId, priorYtd2)
        val result2 = calculatePaycheckDebug(
            input = input2,
            earningConfig = null,
            deductionConfig = repo,
        )
        val hsa2 = result2.deductions.single()
        assertEquals(1_000_00L, hsa2.amount.amount)

        // Third check, prior YTD already at cap -> 0 this period
        val priorYtd3 = result2.ytdAfter
        val input3 = baseInput(employerId, employeeId, priorYtd3)
        val result3 = calculatePaycheckDebug(
            input = input3,
            earningConfig = null,
            deductionConfig = repo,
        )
        assertEquals(0, result3.deductions.size)
    }

    @Test
    fun `garnishment is limited by disposable income`() {
        val employerId = UtilityId("emp-garn")
        val employeeId = CustomerId("ee-garn")

        // Mandatory pre-tax 401k: 50% of gross (10,000) = 5,000
        val pretaxPlanId = "401K_MANDATORY"
        // Garnishment attempts to take 70% of gross (7,000)
        val garnPlanId = "GARNISH_1"

        val repo = object : DeductionConfigRepository {
            override fun findPlansForEmployer(employerId: UtilityId): List<DeductionPlan> = listOf(
                DeductionPlan(
                    id = pretaxPlanId,
                    name = "401k Mandatory",
                    kind = DeductionKind.PRETAX_RETIREMENT_EMPLOYEE,
                    employeeRate = Percent(0.50),
                ),
                DeductionPlan(
                    id = garnPlanId,
                    name = "Wage Garnishment",
                    kind = DeductionKind.GARNISHMENT,
                    employeeRate = Percent(0.70),
                ),
            )
        }

        val input = baseInput(employerId, employeeId, YtdSnapshot(year = 2025))
        val result = calculatePaycheckDebug(
            input = input,
            earningConfig = null,
            deductionConfig = repo,
        )

        // Gross 10,000, pre-tax mandatory = 5,000 -> disposable = 5,000
        // Garnishment tries for 7,000 but must be limited to 5,000
        assertEquals(10_000_00L, result.gross.amount)

        val dedsByName = result.deductions.associateBy { it.description }
        val k401 = dedsByName.getValue("401k Mandatory")
        val garn = dedsByName.getValue("Wage Garnishment")

        assertEquals(5_000_00L, k401.amount.amount)
        assertEquals(5_000_00L, garn.amount.amount)

        // Net = gross - 5,000 - 5,000 = 0 (no taxes here)
        assertEquals(0L, result.net.amount)
    }

    @Test
    fun `multiple pre tax deductions interact correctly with tax bases`() {
        val employerId = UtilityId("emp-multi-pre")
        val employeeId = CustomerId("ee-multi-pre")

        val repo = object : DeductionConfigRepository {
            override fun findPlansForEmployer(employerId: UtilityId): List<DeductionPlan> = listOf(
                DeductionPlan(
                    id = "HSA_MULTI",
                    name = "HSA",
                    kind = DeductionKind.HSA,
                    employeeRate = Percent(0.05), // 5% of 10,000 = 500
                ),
                DeductionPlan(
                    id = "FSA_MULTI",
                    name = "FSA",
                    kind = DeductionKind.FSA,
                    employeeRate = Percent(0.05), // 5% of 10,000 = 500
                ),
                DeductionPlan(
                    id = "401K_MULTI",
                    name = "401k",
                    kind = DeductionKind.PRETAX_RETIREMENT_EMPLOYEE,
                    employeeRate = Percent(0.10), // 10% of 10,000 = 1,000
                ),
            )
        }

        // Simple flat federal tax on FederalTaxable basis at 10%
        val taxRuleId = "EE_MULTI_PRE_TAX"
        val taxRule = TaxRule.FlatRateTax(
            id = taxRuleId,
            jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "US"),
            basis = TaxBasis.FederalTaxable,
            rate = Percent(0.10),
        )

        val input = baseInput(employerId, employeeId, YtdSnapshot(year = 2025)).copy(
            taxContext = TaxContext(federal = listOf(taxRule)),
        )

        val result = calculatePaycheckDebug(
            input = input,
            earningConfig = null,
            deductionConfig = repo,
        )

        assertEquals(10_000_00L, result.gross.amount)

        // Pre-tax deductions: HSA 500 + FSA 500 + 401k 1,000 = 2,000
        val totalPreTax = result.deductions.sumOf { it.amount.amount }
        assertEquals(2_000_00L, totalPreTax)

        // Trace: FederalTaxable basis components should show 2,000 of deductions
        val fedBasisStep = result.trace.steps.filterIsInstance<TraceStep.BasisComputed>()
            .first { it.basis == TaxBasis.FederalTaxable }
        val comps = fedBasisStep.components
        val lessFed = comps["lessFederalTaxableDeductions"]
        assertEquals(2_000_00L, lessFed?.amount)

        // FederalTaxable = 10,000 - 2,000 = 8,000 -> 10% = 800 tax
        val eeTax = result.employeeTaxes.single()
        assertEquals(taxRuleId, eeTax.ruleId)
        assertEquals(800_00L, eeTax.amount.amount)

        // Net = 10,000 - 2,000 - 800 = 7,200
        assertEquals(7_200_00L, result.net.amount)
    }
}
