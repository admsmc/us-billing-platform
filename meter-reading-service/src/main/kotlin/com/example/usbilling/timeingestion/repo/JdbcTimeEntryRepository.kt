package com.example.usbilling.timeingestion.repo

import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Date
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate

@Repository
class JdbcTimeEntryRepository(
    private val jdbcTemplate: JdbcTemplate,
) : TimeEntryRepository {

    private val isPostgres: Boolean by lazy {
        val ds = jdbcTemplate.dataSource ?: return@lazy false
        ds.connection.use { conn ->
            conn.metaData.databaseProductName.lowercase().contains("postgres")
        }
    }

    override fun upsert(employerId: UtilityId, employeeId: CustomerId, entry: TimeEntryRepository.StoredTimeEntry): Boolean {
        val existed = exists(employerId, employeeId, entry.entryId)
        upsertInternal(employerId, employeeId, entry, now = Instant.now())
        return existed
    }

    override fun upsertAll(employerId: UtilityId, employeeId: CustomerId, entries: List<TimeEntryRepository.StoredTimeEntry>): Int {
        if (entries.isEmpty()) return 0

        val existed = countExisting(employerId, employeeId, entries.map { it.entryId })
        val now = Instant.now()
        entries.forEach { upsertInternal(employerId, employeeId, it, now) }
        return existed
    }

    override fun findInRange(employerId: UtilityId, employeeId: CustomerId, start: LocalDate, end: LocalDate): List<TimeEntryRepository.StoredTimeEntry> = jdbcTemplate.query(
        """
            SELECT entry_id, work_date, hours,
                   cash_tips_cents, charged_tips_cents, allocated_tips_cents,
                   commission_cents, bonus_cents, reimbursement_non_taxable_cents,
                   worksite_key
            FROM time_entry
            WHERE employer_id = ?
              AND employee_id = ?
              AND work_date >= ?
              AND work_date <= ?
            ORDER BY work_date, entry_id
        """.trimIndent(),
        { rs, _ -> rs.toStoredTimeEntry() },
        employerId.value,
        employeeId.value,
        Date.valueOf(start),
        Date.valueOf(end),
    )

    override fun findAllInRange(employerId: UtilityId, start: LocalDate, end: LocalDate): List<TimeEntryRepository.StoredTimeEntryWithEmployee> = jdbcTemplate.query(
        """
            SELECT employee_id,
                   entry_id, work_date, hours,
                   cash_tips_cents, charged_tips_cents, allocated_tips_cents,
                   commission_cents, bonus_cents, reimbursement_non_taxable_cents,
                   worksite_key
            FROM time_entry
            WHERE employer_id = ?
              AND work_date >= ?
              AND work_date <= ?
            ORDER BY work_date, employee_id, entry_id
        """.trimIndent(),
        { rs, _ ->
            TimeEntryRepository.StoredTimeEntryWithEmployee(
                employeeId = CustomerId(rs.getString("employee_id")),
                entry = rs.toStoredTimeEntry(),
            )
        },
        employerId.value,
        Date.valueOf(start),
        Date.valueOf(end),
    )

    private fun exists(employerId: UtilityId, employeeId: CustomerId, entryId: String): Boolean {
        val n = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(1)
            FROM time_entry
            WHERE employer_id = ? AND employee_id = ? AND entry_id = ?
            """.trimIndent(),
            Long::class.java,
            employerId.value,
            employeeId.value,
            entryId,
        ) ?: 0L
        return n > 0L
    }

    private fun countExisting(employerId: UtilityId, employeeId: CustomerId, entryIds: List<String>): Int {
        val distinct = entryIds.filter { it.isNotBlank() }.distinct()
        if (distinct.isEmpty()) return 0

        val placeholders = distinct.joinToString(",") { "?" }
        val args = ArrayList<Any>(2 + distinct.size).apply {
            add(employerId.value)
            add(employeeId.value)
            addAll(distinct)
        }

        @Suppress("SpreadOperator")
        val n = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(1)
            FROM time_entry
            WHERE employer_id = ?
              AND employee_id = ?
              AND entry_id IN ($placeholders)
            """.trimIndent(),
            Long::class.java,
            *args.toTypedArray(),
        ) ?: 0L

        return n.toInt()
    }

    private fun upsertInternal(employerId: UtilityId, employeeId: CustomerId, entry: TimeEntryRepository.StoredTimeEntry, now: Instant) {
        val nowTs = Timestamp.from(now)

        if (isPostgres) {
            jdbcTemplate.update(
                """
                INSERT INTO time_entry (
                  employer_id, employee_id, entry_id,
                  work_date, hours,
                  cash_tips_cents, charged_tips_cents, allocated_tips_cents,
                  commission_cents, bonus_cents, reimbursement_non_taxable_cents,
                  worksite_key,
                  created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (employer_id, employee_id, entry_id)
                DO UPDATE SET
                  work_date = EXCLUDED.work_date,
                  hours = EXCLUDED.hours,
                  cash_tips_cents = EXCLUDED.cash_tips_cents,
                  charged_tips_cents = EXCLUDED.charged_tips_cents,
                  allocated_tips_cents = EXCLUDED.allocated_tips_cents,
                  commission_cents = EXCLUDED.commission_cents,
                  bonus_cents = EXCLUDED.bonus_cents,
                  reimbursement_non_taxable_cents = EXCLUDED.reimbursement_non_taxable_cents,
                  worksite_key = EXCLUDED.worksite_key,
                  updated_at = EXCLUDED.updated_at
                """.trimIndent(),
                employerId.value,
                employeeId.value,
                entry.entryId,
                Date.valueOf(entry.date),
                entry.hours,
                entry.cashTipsCents,
                entry.chargedTipsCents,
                entry.allocatedTipsCents,
                entry.commissionCents,
                entry.bonusCents,
                entry.reimbursementNonTaxableCents,
                entry.worksiteKey,
                nowTs,
                nowTs,
            )
            return
        }

        // H2 fallback for tests.
        jdbcTemplate.update(
            """
            MERGE INTO time_entry (
              employer_id, employee_id, entry_id,
              work_date, hours,
              cash_tips_cents, charged_tips_cents, allocated_tips_cents,
              commission_cents, bonus_cents, reimbursement_non_taxable_cents,
              worksite_key,
              created_at, updated_at
            ) KEY (employer_id, employee_id, entry_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            employerId.value,
            employeeId.value,
            entry.entryId,
            Date.valueOf(entry.date),
            entry.hours,
            entry.cashTipsCents,
            entry.chargedTipsCents,
            entry.allocatedTipsCents,
            entry.commissionCents,
            entry.bonusCents,
            entry.reimbursementNonTaxableCents,
            entry.worksiteKey,
            nowTs,
            nowTs,
        )
    }

    private fun ResultSet.toStoredTimeEntry(): TimeEntryRepository.StoredTimeEntry = TimeEntryRepository.StoredTimeEntry(
        entryId = getString("entry_id"),
        date = getDate("work_date").toLocalDate(),
        hours = getDouble("hours"),
        cashTipsCents = getLong("cash_tips_cents"),
        chargedTipsCents = getLong("charged_tips_cents"),
        allocatedTipsCents = getLong("allocated_tips_cents"),
        commissionCents = getLong("commission_cents"),
        bonusCents = getLong("bonus_cents"),
        reimbursementNonTaxableCents = getLong("reimbursement_non_taxable_cents"),
        worksiteKey = getString("worksite_key"),
    )
}
