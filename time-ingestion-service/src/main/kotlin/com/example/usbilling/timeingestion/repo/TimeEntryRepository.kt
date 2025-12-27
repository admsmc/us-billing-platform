package com.example.usbilling.timeingestion.repo

import com.example.usbilling.shared.EmployeeId
import com.example.usbilling.shared.EmployerId
import java.time.LocalDate

interface TimeEntryRepository {

    data class StoredTimeEntry(
        val entryId: String,
        val date: LocalDate,
        val hours: Double,
        val cashTipsCents: Long = 0L,
        val chargedTipsCents: Long = 0L,
        val allocatedTipsCents: Long = 0L,
        val commissionCents: Long = 0L,
        val bonusCents: Long = 0L,
        val reimbursementNonTaxableCents: Long = 0L,
        val worksiteKey: String? = null,
    )

    data class StoredTimeEntryWithEmployee(
        val employeeId: EmployeeId,
        val entry: StoredTimeEntry,
    )

    /** @return true if entry existed and was updated; false if inserted */
    fun upsert(employerId: EmployerId, employeeId: EmployeeId, entry: StoredTimeEntry): Boolean

    /** @return count of entries that already existed (were updated) */
    fun upsertAll(employerId: EmployerId, employeeId: EmployeeId, entries: List<StoredTimeEntry>): Int

    fun findInRange(employerId: EmployerId, employeeId: EmployeeId, start: LocalDate, end: LocalDate): List<StoredTimeEntry>

    fun findAllInRange(employerId: EmployerId, start: LocalDate, end: LocalDate): List<StoredTimeEntryWithEmployee>
}
