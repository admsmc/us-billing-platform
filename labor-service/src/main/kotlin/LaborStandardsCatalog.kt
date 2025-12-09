package com.example.uspayroll.labor.api

import com.example.uspayroll.shared.EmployerId
import java.time.LocalDate

// (content copied from previous tax-service LaborStandardsCatalog.kt)

data class LaborStandardsQuery(
    val employerId: EmployerId,
    val asOfDate: LocalDate,
    val workState: String? = null,
    val homeState: String? = null,
)

enum class LaborStandardSourceKind {
    FEDERAL_DOL_MIN_WAGE_TABLE,
    FEDERAL_DOL_TIPPED_WAGE_TABLE,
    STATE_STATUTE,
    STATE_ADMIN_RULE,
    OTHER,
}

data class LaborStandardSourceRef(
    val kind: LaborStandardSourceKind,
    val citation: String? = null,
    val url: String? = null,
)

data class StateLaborStandard(
    val stateCode: String,
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate? = null,
    val regularMinimumWageCents: Long?,
    val tippedMinimumCashWageCents: Long? = null,
    val maxTipCreditCents: Long? = null,
    val weeklyOvertimeThresholdHours: Double = 40.0,
    val dailyOvertimeThresholdHours: Double? = null,
    val dailyDoubleTimeThresholdHours: Double? = null,
    val sources: List<LaborStandardSourceRef> = emptyList(),
)

interface LaborStandardsCatalog {
    fun loadStateStandard(query: LaborStandardsQuery): StateLaborStandard?
    fun listStateStandards(asOfDate: LocalDate? = null): List<StateLaborStandard>
}
