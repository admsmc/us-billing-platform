package com.example.usbilling.tax.api

import com.example.usbilling.billing.model.RateContext
import com.example.usbilling.shared.UtilityId
import java.time.LocalDate

/**
 * Boundary interfaces exposed by the rate service.
 * The worker/orchestrator call this to obtain rate tariffs and schedules.
 *
 * Note: Renamed from TaxContextProvider (payroll) to RateContextProvider (billing).
 */

/** Provides RateContext for a given utility and effective date. */
interface RateContextProvider {
    /**
     * Returns a RateContext that includes all relevant rate schedules and regulatory charges
     * for the given utility as of [asOfDate].
     *
     * @param utilityId The utility company
     * @param asOfDate Date to evaluate rate applicability
     * @param serviceState State where service is provided (for rate jurisdiction)
     */
    fun getRateContext(utilityId: UtilityId, asOfDate: LocalDate, serviceState: String): RateContext
}

// Alias for backward compatibility
@Deprecated("Use RateContextProvider instead", ReplaceWith("RateContextProvider"))
typealias TaxContextProvider = RateContextProvider
