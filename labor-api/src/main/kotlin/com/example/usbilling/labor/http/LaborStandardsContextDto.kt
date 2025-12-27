package com.example.usbilling.labor.http

import com.example.usbilling.payroll.model.LaborStandardsContext
import com.example.usbilling.shared.Money

/**
 * Stable wire DTO for labor-service HTTP responses.
 */

data class LaborStandardsContextDto(
    val federalMinimumWageCents: Long,
    val youthMinimumWageCents: Long? = null,
    val youthMaxAgeYears: Int? = null,
    val youthMaxConsecutiveDaysFromHire: Int? = null,
    val federalTippedCashMinimumCents: Long? = null,
    val tippedMonthlyThresholdCents: Long? = null,
)

fun LaborStandardsContext.toDto(): LaborStandardsContextDto = LaborStandardsContextDto(
    federalMinimumWageCents = federalMinimumWage.amount,
    youthMinimumWageCents = youthMinimumWage?.amount,
    youthMaxAgeYears = youthMaxAgeYears,
    youthMaxConsecutiveDaysFromHire = youthMaxConsecutiveDaysFromHire,
    federalTippedCashMinimumCents = federalTippedCashMinimum?.amount,
    tippedMonthlyThresholdCents = tippedMonthlyThreshold?.amount,
)

fun LaborStandardsContextDto.toDomain(): LaborStandardsContext = LaborStandardsContext(
    federalMinimumWage = Money(federalMinimumWageCents),
    youthMinimumWage = youthMinimumWageCents?.let { Money(it) },
    youthMaxAgeYears = youthMaxAgeYears,
    youthMaxConsecutiveDaysFromHire = youthMaxConsecutiveDaysFromHire,
    federalTippedCashMinimum = federalTippedCashMinimumCents?.let { Money(it) },
    tippedMonthlyThreshold = tippedMonthlyThresholdCents?.let { Money(it) },
)
