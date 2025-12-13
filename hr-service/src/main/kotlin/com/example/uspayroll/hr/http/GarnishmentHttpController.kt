package com.example.uspayroll.hr.http

import com.example.uspayroll.hr.db.GarnishmentRuleConfigRepository
import com.example.uspayroll.hr.garnishment.GarnishmentOrderRepository
import com.example.uspayroll.shared.EmployeeId
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
 * Controller that exposes a /garnishments endpoint compatible with the
 * worker-service HttpHrClient expectations. Orders are sourced from the
 * HR database (garnishment_order) and combined with statutory rule
 * configuration to produce full GarnishmentOrderDto objects.
 */
@RestController
@RequestMapping("/employers/{employerId}")
class GarnishmentHttpController(
    private val ruleConfigRepository: GarnishmentRuleConfigRepository,
    private val orderRepository: GarnishmentOrderRepository,
) {

    @GetMapping("/employees/{employeeId}/garnishments")
    fun getGarnishments(
        @PathVariable employerId: String,
        @PathVariable employeeId: String,
        @RequestParam("asOf") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) asOf: LocalDate,
    ): List<GarnishmentOrderDto> {
        val employer = EmployerId(employerId)
        val employee = EmployeeId(employeeId)

        val activeOrders = orderRepository.findActiveOrdersForEmployee(employer, employee, asOf)
        val rules = ruleConfigRepository.findRulesForEmployer(employer)

        // Legacy fallback: only synthesize if there are no persisted orders at all.
        // If the employee has orders but none are active (e.g., all COMPLETED),
        // return an empty list.
        if (activeOrders.isEmpty()) {
            if (orderRepository.hasAnyOrdersForEmployee(employer, employee)) {
                return emptyList()
            }
            if (rules.isEmpty()) return emptyList()

            return rules.mapIndexed { index, rule ->
                GarnishmentOrderDto(
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
                    supportsOtherDependents = null,
                    arrearsAtLeast12Weeks = null,
                )
            }
        }

        val rulesByKey = rules.groupBy { it.type to it.jurisdiction }

        return activeOrders.mapNotNull { orderRow ->
            val matchingRule = rulesByKey[orderRow.type to orderRow.issuingJurisdiction]
                ?.firstOrNull()
                ?: rulesByKey[orderRow.type to null]?.firstOrNull()

            // Prefer per-order overrides when present; fall back to rule-derived
            // configuration for backward compatibility.
            val formula = orderRow.formulaOverride ?: matchingRule?.formula
            val protectedRule = orderRow.protectedEarningsRuleOverride ?: matchingRule?.protectedEarningsRule

            if (formula == null) {
                // If we cannot determine a formula, skip this order rather than
                // returning a partially populated response.
                return@mapNotNull null
            }

            GarnishmentOrderDto(
                orderId = orderRow.orderId,
                planId = "GARN_PLAN_${orderRow.type.name}",
                type = orderRow.type,
                issuingJurisdiction = orderRow.issuingJurisdiction ?: matchingRule?.jurisdiction,
                caseNumber = orderRow.caseNumber,
                servedDate = orderRow.servedDate,
                endDate = orderRow.endDate,
                priorityClass = orderRow.priorityClass,
                sequenceWithinClass = orderRow.sequenceWithinClass,
                formula = formula,
                protectedEarningsRule = protectedRule,
                arrearsBefore = orderRow.currentArrears ?: orderRow.initialArrears,
                lifetimeCap = null,
                supportsOtherDependents = orderRow.supportsOtherDependents,
                arrearsAtLeast12Weeks = orderRow.arrearsAtLeast12Weeks,
            )
        }
    }
}
