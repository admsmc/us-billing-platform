package com.example.usbilling.tax.http

import com.example.usbilling.payroll.model.TaxContext
import com.example.usbilling.shared.EmployerId
import com.example.usbilling.tax.api.TaxCatalog
import com.example.usbilling.tax.api.TaxQuery
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
 *   GET /employers/{employerId}/tax-context?asOf=YYYY-MM-DD&residentState=&workState=&locality=CODE
 * which returns the effective TaxContext for an employer/date and optional
 * employee context filters, serialized as a stable DTO.
 */
@RestController
@RequestMapping("/employers/{employerId}")
class TaxHttpController(
    private val taxCatalog: TaxCatalog,
) {

    @GetMapping("/tax-context")
    fun getTaxContext(
        @PathVariable employerId: String,
        @RequestParam("asOf") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) asOf: LocalDate,
        @RequestParam("residentState", required = false) residentState: String?,
        @RequestParam("workState", required = false) workState: String?,
        @RequestParam("locality", required = false) locality: List<String>?,
    ): TaxContextDto {
        val query = TaxQuery(
            employerId = EmployerId(employerId),
            asOfDate = asOf,
            residentState = residentState,
            workState = workState,
            localJurisdictions = locality.orEmpty(),
        )

        val rules = taxCatalog.loadRules(query)

        val context = TaxContext(
            federal = rules.filter { it.jurisdiction.type == com.example.usbilling.payroll.model.TaxJurisdictionType.FEDERAL },
            state = rules.filter { it.jurisdiction.type == com.example.usbilling.payroll.model.TaxJurisdictionType.STATE },
            local = rules.filter { it.jurisdiction.type == com.example.usbilling.payroll.model.TaxJurisdictionType.LOCAL },
            employerSpecific = rules.filter { it.jurisdiction.type == com.example.usbilling.payroll.model.TaxJurisdictionType.OTHER },
        )

        return context.toDto()
    }
}
