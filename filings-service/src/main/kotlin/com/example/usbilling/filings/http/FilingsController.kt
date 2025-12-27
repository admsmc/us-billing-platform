package com.example.usbilling.filings.http

import com.example.usbilling.filings.service.FilingsComputationService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/employers/{employerId}/filings")
class FilingsController(
    private val filings: FilingsComputationService,
) {

    @GetMapping("/federal/941")
    fun form941(
        @PathVariable employerId: String,
        @RequestParam year: Int,
        @RequestParam quarter: Int,
    ): FilingsComputationService.Form941Quarterly = filings.compute941(employerId, year, quarter)

    @GetMapping("/federal/940")
    fun form940(
        @PathVariable employerId: String,
        @RequestParam year: Int,
    ): FilingsComputationService.Form940Annual = filings.compute940(employerId, year)

    @GetMapping("/w2")
    fun w2s(
        @PathVariable employerId: String,
        @RequestParam year: Int,
    ): List<FilingsComputationService.W2EmployeeAnnual> = filings.computeW2s(employerId, year)

    @GetMapping("/w2/{employeeId}")
    fun w2ForEmployee(
        @PathVariable employerId: String,
        @PathVariable employeeId: String,
        @RequestParam year: Int,
    ): FilingsComputationService.W2EmployeeAnnual = filings
        .computeW2s(employerId, year)
        .firstOrNull { it.employeeId == employeeId }
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "w2 not found")

    @GetMapping("/w3")
    fun w3(
        @PathVariable employerId: String,
        @RequestParam year: Int,
    ): FilingsComputationService.W3Annual = filings.computeW3(employerId, year)

    @GetMapping("/state/withholding")
    fun stateWithholding(
        @PathVariable employerId: String,
        @RequestParam year: Int,
        @RequestParam quarter: Int,
    ): FilingsComputationService.StateWithholdingQuarterly = filings.computeStateWithholding(employerId, year, quarter)

    /**
     * Lightweight validation hook for ops/reconciliation workflows.
     *
     * This does not compute filings; it verifies that the underlying ledger + payment status
     * projections are present and consistent enough to proceed.
     */
    @GetMapping("/validate")
    fun validate(
        @PathVariable employerId: String,
        @RequestParam year: Int,
        @RequestParam(required = false) quarter: Int?,
    ): FilingsComputationService.FilingsValidation {
        if (quarter != null && (quarter < 1 || quarter > 4)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "quarter must be in 1..4")
        }
        return filings.validateFilingsBaseData(employerId, year, quarter)
    }
}
