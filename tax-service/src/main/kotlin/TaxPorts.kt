package com.example.uspayroll.tax.api

import com.example.uspayroll.payroll.model.TaxContext
import com.example.uspayroll.shared.EmployerId
import java.time.LocalDate

/**
 * Boundary interfaces exposed by the tax service.
 * The worker/orchestrator call this to obtain tax rules/statutory context.
 */

/** Provides TaxContext for a given employer and effective date. */
interface TaxContextProvider {
    /**
     * Returns a TaxContext that includes all relevant federal/state/local/employer-specific rules
     * for the given employer as of [asOfDate].
     */
    fun getTaxContext(employerId: EmployerId, asOfDate: LocalDate): TaxContext
}
