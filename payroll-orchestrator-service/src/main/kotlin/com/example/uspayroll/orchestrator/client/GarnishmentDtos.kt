package com.example.uspayroll.orchestrator.client

import com.example.uspayroll.payroll.model.TaxJurisdiction
import com.example.uspayroll.payroll.model.garnishment.GarnishmentContext
import com.example.uspayroll.payroll.model.garnishment.GarnishmentFormula
import com.example.uspayroll.payroll.model.garnishment.GarnishmentOrder
import com.example.uspayroll.payroll.model.garnishment.GarnishmentOrderId
import com.example.uspayroll.payroll.model.garnishment.GarnishmentType
import com.example.uspayroll.payroll.model.garnishment.ProtectedEarningsRule
import com.example.uspayroll.shared.Money
import java.time.LocalDate

data class GarnishmentOrderDto(
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
    val protectedEarningsRule: ProtectedEarningsRule? = null,
    val arrearsBefore: Money? = null,
    val lifetimeCap: Money? = null,
    val supportsOtherDependents: Boolean? = null,
    val arrearsAtLeast12Weeks: Boolean? = null,
)

fun GarnishmentOrderDto.toDomain(): GarnishmentOrder =
    GarnishmentOrder(
        orderId = GarnishmentOrderId(orderId),
        planId = planId,
        type = type,
        issuingJurisdiction = issuingJurisdiction,
        caseNumber = caseNumber,
        servedDate = servedDate,
        endDate = endDate,
        priorityClass = priorityClass,
        sequenceWithinClass = sequenceWithinClass,
        formula = formula,
        protectedEarningsRule = protectedEarningsRule,
        arrearsBefore = arrearsBefore,
        lifetimeCap = lifetimeCap,
        supportsOtherDependents = supportsOtherDependents,
        arrearsAtLeast12Weeks = arrearsAtLeast12Weeks,
    )

fun List<GarnishmentOrderDto>.toDomainContext(): GarnishmentContext =
    GarnishmentContext(orders = this.map { it.toDomain() })
