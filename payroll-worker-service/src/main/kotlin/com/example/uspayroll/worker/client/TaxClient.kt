package com.example.uspayroll.worker.client

import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.payroll.model.TaxContext
import java.time.LocalDate

/**
 * Client-side abstraction for talking to the Tax service.
 */
interface TaxClient {
    fun getTaxContext(
        employerId: EmployerId,
        asOfDate: LocalDate,
    ): TaxContext
}
