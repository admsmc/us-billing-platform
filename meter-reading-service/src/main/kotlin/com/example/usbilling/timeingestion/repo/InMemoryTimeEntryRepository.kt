package com.example.usbilling.timeingestion.repo

import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

@Component
class InMemoryTimeEntryRepository {

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

    private data class Key(val employerId: String, val employeeId: String)

    private val byEmployee: ConcurrentHashMap<Key, ConcurrentHashMap<String, StoredTimeEntry>> = ConcurrentHashMap()

    fun upsert(employerId: UtilityId, employeeId: CustomerId, entry: StoredTimeEntry): Boolean {
        val m = byEmployee.computeIfAbsent(Key(employerId.value, employeeId.value)) { ConcurrentHashMap() }
        val existed = m.containsKey(entry.entryId)
        m[entry.entryId] = entry
        return existed
    }

    fun upsertAll(employerId: UtilityId, employeeId: CustomerId, entries: List<StoredTimeEntry>): Int {
        if (entries.isEmpty()) return 0
        val m = byEmployee.computeIfAbsent(Key(employerId.value, employeeId.value)) { ConcurrentHashMap() }
        var existed = 0
        for (e in entries) {
            if (m.containsKey(e.entryId)) existed += 1
            m[e.entryId] = e
        }
        return existed
    }

    fun findInRange(employerId: UtilityId, employeeId: CustomerId, start: LocalDate, end: LocalDate): List<StoredTimeEntry> {
        val m = byEmployee[Key(employerId.value, employeeId.value)] ?: return emptyList()
        return m.values
            .asSequence()
            .filter { !it.date.isBefore(start) && !it.date.isAfter(end) }
            .sortedBy { it.date }
            .toList()
    }

    data class StoredTimeEntryWithEmployee(
        val employeeId: CustomerId,
        val entry: StoredTimeEntry,
    )

    fun findAllInRange(employerId: UtilityId, start: LocalDate, end: LocalDate): List<StoredTimeEntryWithEmployee> {
        val out = ArrayList<StoredTimeEntryWithEmployee>()
        for ((k, m) in byEmployee) {
            if (k.employerId != employerId.value) continue
            val employee = CustomerId(k.employeeId)
            for (e in m.values) {
                if (e.date.isBefore(start) || e.date.isAfter(end)) continue
                out.add(StoredTimeEntryWithEmployee(employeeId = employee, entry = e))
            }
        }
        out.sortBy { it.entry.date }
        return out
    }
}
