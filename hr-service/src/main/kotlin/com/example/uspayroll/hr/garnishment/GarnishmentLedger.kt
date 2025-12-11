package com.example.uspayroll.hr.garnishment

import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * Simple in-memory implementation of an arrears ledger for garnishment
 * withholdings. This is intended as a reference implementation for the
 * external arrears ledger described in the garnishment plan. In a production
 * deployment, this component can be replaced with a persistent implementation
 * backed by a database.
 */
data class GarnishmentLedgerEntry(
    val employerId: EmployerId,
    val employeeId: EmployeeId,
    val orderId: String,
    /** Total amount withheld across all recorded events for this order. */
    val totalWithheld: Money,
    /** Optional starting arrears balance, if known. */
    val initialArrears: Money? = null,
    /** Remaining arrears based on initialArrears minus totalWithheld, if tracked. */
    val remainingArrears: Money? = initialArrears?.let { Money(it.amount - totalWithheld.amount) },
    val lastCheckDate: LocalDate? = null,
    val lastPaycheckId: String? = null,
    val lastPayRunId: String? = null,
)

interface GarnishmentLedgerRepository {
    /**
     * Apply a batch of withholding events for a single employee and employer,
     * updating per-order ledger entries.
     */
    fun recordWithholdings(
        employerId: EmployerId,
        employeeId: EmployeeId,
        events: List<GarnishmentWithholdingEventView>,
    )

    /**
        * Return the current ledger entries for an employee, keyed by order id.
        */
    fun findByEmployee(
        employerId: EmployerId,
        employeeId: EmployeeId,
    ): Map<String, GarnishmentLedgerEntry>
}

/**
 * Lightweight view of a withholding event, mirroring the structure received
 * by the HR API without tying this component to the HTTP DTO types.
 */
data class GarnishmentWithholdingEventView(
    val orderId: String,
    val paycheckId: String,
    val payRunId: String?,
    val checkDate: LocalDate,
    val withheld: Money,
    val netPay: Money,
)

class InMemoryGarnishmentLedgerRepository : GarnishmentLedgerRepository {

    private data class Key(val employerId: String, val employeeId: String, val orderId: String)

    private val store = ConcurrentHashMap<Key, GarnishmentLedgerEntry>()

    override fun recordWithholdings(
        employerId: EmployerId,
        employeeId: EmployeeId,
        events: List<GarnishmentWithholdingEventView>,
    ) {
        events.forEach { event ->
            val key = Key(employerId.value, employeeId.value, event.orderId)
            val existing = store[key]
            val newTotal = (existing?.totalWithheld?.amount ?: 0L) + event.withheld.amount
            val initialArrears = existing?.initialArrears
            val remaining = initialArrears?.let { Money(it.amount - newTotal) }

            store[key] = GarnishmentLedgerEntry(
                employerId = employerId,
                employeeId = employeeId,
                orderId = event.orderId,
                totalWithheld = Money(newTotal),
                initialArrears = initialArrears,
                remainingArrears = remaining,
                lastCheckDate = event.checkDate,
                lastPaycheckId = event.paycheckId,
                lastPayRunId = event.payRunId,
            )
        }
    }

    override fun findByEmployee(
        employerId: EmployerId,
        employeeId: EmployeeId,
    ): Map<String, GarnishmentLedgerEntry> {
        return store.filterKeys { it.employerId == employerId.value && it.employeeId == employeeId.value }
            .mapKeys { (key, _) -> key.orderId }
    }
}
