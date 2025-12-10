package com.example.uspayroll.tax.http

import com.example.uspayroll.payroll.model.TaxContext
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.tax.api.TaxContextProvider
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/**
 * HTTP API for tax-service.
 *
 * Primary endpoint:
 *   GET /employers/{employerId}/tax-context?asOf=YYYY-MM-DD
 * which returns the effective TaxContext for an employer/date.
 */
@RestController
@RequestMapping("/employers/{employerId}")
class TaxHttpController(
    private val taxContextProvider: TaxContextProvider,
) {

    @GetMapping("/tax-context")
    fun getTaxContext(
        @PathVariable employerId: String,
        @RequestParam("asOf") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) asOf: LocalDate,
    ): TaxContext {
        return taxContextProvider.getTaxContext(
            employerId = EmployerId(employerId),
            asOfDate = asOf,
        )
    }
}
