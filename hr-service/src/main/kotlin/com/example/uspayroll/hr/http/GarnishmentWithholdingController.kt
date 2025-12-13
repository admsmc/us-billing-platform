package com.example.uspayroll.hr.http

import com.example.uspayroll.hr.garnishment.GarnishmentLedgerRepository
import com.example.uspayroll.hr.garnishment.GarnishmentReconciliationService
import com.example.uspayroll.hr.garnishment.GarnishmentWithholdingEventView
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@RequestMapping("/employers/{employerId}")
class GarnishmentWithholdingController(
    private val ledgerRepository: GarnishmentLedgerRepository,
    private val reconciliationService: GarnishmentReconciliationService,
) {

    @PostMapping("/employees/{employeeId}/garnishments/withholdings")
    fun recordWithholdings(@PathVariable employerId: String, @PathVariable employeeId: String, @RequestBody request: GarnishmentWithholdingRequest) {
        val employer = EmployerId(employerId)
        val employee = EmployeeId(employeeId)
        val views = request.events.map {
            GarnishmentWithholdingEventView(
                orderId = it.orderId,
                paycheckId = it.paycheckId,
                payRunId = it.payRunId,
                checkDate = it.checkDate,
                withheld = it.withheld,
                netPay = it.netPay,
            )
        }
        ledgerRepository.recordWithholdings(employer, employee, views)
        reconciliationService.reconcileForEmployee(employer, employee)
    }

    /**
     * Read-only endpoint to inspect the current in-memory garnishment arrears
     * ledger for a given employee. Intended for debugging and operational
     * visibility; not part of the worker-service contract.
     */
    @GetMapping("/employees/{employeeId}/garnishments/ledger")
    fun getLedger(@PathVariable employerId: String, @PathVariable employeeId: String): Map<String, GarnishmentLedgerEntryHr> {
        val employer = EmployerId(employerId)
        val employee = EmployeeId(employeeId)
        val entries = ledgerRepository.findByEmployee(employer, employee)
        return entries.mapValues { (_, entry) ->
            GarnishmentLedgerEntryHr(
                orderId = entry.orderId,
                totalWithheld = entry.totalWithheld,
                initialArrears = entry.initialArrears,
                remainingArrears = entry.remainingArrears,
                lastCheckDate = entry.lastCheckDate,
                lastPaycheckId = entry.lastPaycheckId,
                lastPayRunId = entry.lastPayRunId,
            )
        }
    }
}

/**
 * Read-only view of a ledger entry exposed over HTTP for debugging/ops.
 */
data class GarnishmentLedgerEntryHr(
    val orderId: String,
    val totalWithheld: Money,
    val initialArrears: Money?,
    val remainingArrears: Money?,
    val lastCheckDate: LocalDate?,
    val lastPaycheckId: String?,
    val lastPayRunId: String?,
)
