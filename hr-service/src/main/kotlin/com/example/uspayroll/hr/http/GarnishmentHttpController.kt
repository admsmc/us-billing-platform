package com.example.uspayroll.hr.http

import com.example.uspayroll.hr.db.GarnishmentRuleConfigRepository
import com.example.uspayroll.payroll.model.Percent
import com.example.uspayroll.payroll.model.TaxJurisdiction
import com.example.uspayroll.payroll.model.TaxJurisdictionType
import com.example.uspayroll.payroll.model.garnishment.GarnishmentFormula
import com.example.uspayroll.payroll.model.garnishment.GarnishmentType
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/**
 * Minimal controller that exposes a /garnishments endpoint compatible with the
 * worker-service HttpHrClient expectations. Phase 4 wires this to a simple
 * in-memory GarnishmentRuleConfigRepository rather than hard-coding values.
 */
@RestController
@RequestMapping("/employers/{employerId}")
class GarnishmentHttpController(
    private val ruleConfigRepository: GarnishmentRuleConfigRepository,
) {

    @GetMapping("/employees/{employeeId}/garnishments")
    fun getGarnishments(
        @PathVariable employerId: String,
        @PathVariable employeeId: String,
        @RequestParam("asOf") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) asOf: LocalDate,
    ): List<GarnishmentOrderResponse> {
        val rules = ruleConfigRepository.findRulesForEmployer(EmployerId(employerId))
        if (rules.isEmpty()) return emptyList()

        // For now, synthesize a single order per rule. In a real
        // implementation, rules would be combined with per-employee order
        // records (case numbers, arrears, etc.).
        return rules.mapIndexed { index, rule ->
            GarnishmentOrderResponse(
                orderId = "ORDER-RULE-${index + 1}",
                planId = "GARN_PLAN_${rule.type.name}",
                type = rule.type,
                issuingJurisdiction = rule.jurisdiction,
                caseNumber = null,
                servedDate = asOf.minusDays(30),
                endDate = null,
                priorityClass = 0,
                sequenceWithinClass = 0,
                formula = rule.formula,
                protectedEarningsRule = rule.protectedEarningsRule,
                arrearsBefore = Money(0L),
                lifetimeCap = null,
            )
        }
    }
}

/**
 * HR-service side DTO matching the JSON shape expected by
 * worker-service's GarnishmentOrderDto. The two modules do not share the
 * class directly, but as long as the fields and types line up, Jackson can
 * map between them.
 */
data class GarnishmentOrderResponse(
    val orderId: String,
    val planId: String,
    val type: GarnishmentType,
    val issuingJurisdiction: TaxJurisdiction? = null,
    val caseNumber: String? = null,
    val servedDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val priorityClass: Int = 0,
    val sequenceWithinClass: Int = 0,
    val formula: GarnishmentFormula,
    val protectedEarningsRule: com.example.uspayroll.payroll.model.garnishment.ProtectedEarningsRule? = null,
    val arrearsBefore: Money? = null,
    val lifetimeCap: Money? = null,
)