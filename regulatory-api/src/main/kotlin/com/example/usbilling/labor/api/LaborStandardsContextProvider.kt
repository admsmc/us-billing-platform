package com.example.usbilling.labor.api

import com.example.usbilling.billing.model.RegulatoryContext
import com.example.usbilling.shared.UtilityId
import java.time.LocalDate

/**
 * Provides engine-facing RegulatoryContext for a given utility/date/jurisdiction.
 *
 * Note: Renamed from LaborStandardsContextProvider (payroll) to RegulatoryContextProvider (billing).
 * Labor standards (FLSA, minimum wage) are payroll-specific and removed.
 */
interface RegulatoryContextProvider {
    /**
     * Returns regulatory context including PUC charges and jurisdiction-specific rules.
     *
     * @param utilityId The utility company
     * @param asOfDate Date to evaluate regulatory applicability
     * @param jurisdiction State/locality code (e.g., "MI", "OH", "CA")
     */
    fun getRegulatoryContext(utilityId: UtilityId, asOfDate: LocalDate, jurisdiction: String): RegulatoryContext
}

// Alias for backward compatibility
@Deprecated("Use RegulatoryContextProvider instead", ReplaceWith("RegulatoryContextProvider"))
typealias LaborStandardsContextProvider = RegulatoryContextProvider
