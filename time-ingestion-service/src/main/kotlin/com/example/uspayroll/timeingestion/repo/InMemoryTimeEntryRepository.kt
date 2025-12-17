package com.example.uspayroll.timeingestion.repo

import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

@Component
class InMemoryTimeEntryRepository {

    data class StoredTimeEntry(
        val entryId: String,
        val date: LocalDate,
        val hours: Double,
        val worksiteKey: String? = null,
    )

    private data class Key(val employerId: String, val employeeId: String)

    private val byEmployee: ConcurrentHashMap<Key, ConcurrentHashMap<String, StoredTimeEntry>> = ConcurrentHashMap()

    fun upsert(employerId: EmployerId, employeeId: EmployeeId, entry: StoredTimeEntry): Boolean {
        val m = byEmployee.computeIfAbsent(Key(employerId.value, employeeId.value)) { ConcurrentHashMap() }
        val existed = m.containsKey(entry.entryId)
        m[entry.entryId] = entry
        return existed
    }

    fun upsertAll(employerId: EmployerId, employeeId: EmployeeId, entries: List<StoredTimeEntry>): Int {
        if (entries.isEmpty()) return 0
        val m = byEmployee.computeIfAbsent(Key(employerId.value, employeeId.value)) { ConcurrentHashMap() }
        var existed = 0
        for (e in entries) {
            if (m.containsKey(e.entryId)) existed += 1
            m[e.entryId] = e
        }
        return existed
    }

    fun findInRange(employerId: EmployerId, employeeId: EmployeeId, start: LocalDate, end: LocalDate): List<StoredTimeEntry> {
        val m = byEmployee[Key(employerId.value, employeeId.value)] ?: return emptyList()
        return m.values
            .asSequence()
            .filter { !it.date.isBefore(start) && !it.date.isAfter(end) }
            .sortedBy { it.date }
            .toList()
    }
}
