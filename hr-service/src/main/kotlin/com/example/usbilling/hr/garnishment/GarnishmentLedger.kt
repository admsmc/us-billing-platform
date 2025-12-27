package com.example.usbilling.hr.garnishment

import com.example.usbilling.shared.EmployeeId
import com.example.usbilling.shared.EmployerId
import com.example.usbilling.shared.Money
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
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
    fun recordWithholdings(employerId: EmployerId, employeeId: EmployeeId, events: List<GarnishmentWithholdingEventView>)

    /**
     * Return the current ledger entries for an employee, keyed by order id.
     */
    fun findByEmployee(employerId: EmployerId, employeeId: EmployeeId): Map<String, GarnishmentLedgerEntry>
}

/**
 * JDBC-based implementation of [GarnishmentLedgerRepository] that persists
 * ledger state and event history in the HR service database.
 */
@Repository
class JdbcGarnishmentLedgerRepository(
    private val jdbcTemplate: JdbcTemplate,
) : GarnishmentLedgerRepository {

    private val isPostgres: Boolean by lazy {
        val ds = jdbcTemplate.dataSource ?: return@lazy false
        ds.connection.use { conn ->
            conn.metaData.databaseProductName.lowercase().contains("postgres")
        }
    }

    override fun recordWithholdings(employerId: EmployerId, employeeId: EmployeeId, events: List<GarnishmentWithholdingEventView>) {
        if (events.isEmpty()) return

        events.forEach { event ->
            // Insert event history idempotently. If the event is a duplicate,
            // DO NOT apply it to the aggregate ledger (otherwise we'd double-count).
            val insertedEventRows = if (isPostgres) {
                jdbcTemplate.update(
                    """
                    INSERT INTO garnishment_withholding_event (
                      employer_id, employee_id, order_id,
                      paycheck_id, pay_run_id, check_date,
                      withheld_cents, net_pay_cents
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """.trimIndent(),
                    employerId.value,
                    employeeId.value,
                    event.orderId,
                    event.paycheckId,
                    event.payRunId,
                    event.checkDate,
                    event.withheld.amount,
                    event.netPay.amount,
                )
            } else {
                try {
                    jdbcTemplate.update(
                        """
                        INSERT INTO garnishment_withholding_event (
                          employer_id, employee_id, order_id,
                          paycheck_id, pay_run_id, check_date,
                          withheld_cents, net_pay_cents
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        employerId.value,
                        employeeId.value,
                        event.orderId,
                        event.paycheckId,
                        event.payRunId,
                        event.checkDate,
                        event.withheld.amount,
                        event.netPay.amount,
                    )
                } catch (_: DataIntegrityViolationException) {
                    0
                }
            }

            if (insertedEventRows != 1) return@forEach

            // Increment aggregate ledger.
            val updated = jdbcTemplate.update(
                """
                UPDATE garnishment_ledger
                SET total_withheld_cents = total_withheld_cents + ?,
                    last_check_date = ?,
                    last_paycheck_id = ?,
                    last_pay_run_id = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE employer_id = ?
                  AND employee_id = ?
                  AND order_id = ?
                """.trimIndent(),
                event.withheld.amount,
                event.checkDate,
                event.paycheckId,
                event.payRunId,
                employerId.value,
                employeeId.value,
                event.orderId,
            )

            if (updated == 0) {
                val insertedLedgerRows = if (isPostgres) {
                    jdbcTemplate.update(
                        """
                        INSERT INTO garnishment_ledger (
                          employer_id, employee_id, order_id,
                          total_withheld_cents, initial_arrears_cents, remaining_arrears_cents,
                          last_check_date, last_paycheck_id, last_pay_run_id,
                          created_at, updated_at
                        ) VALUES (?, ?, ?, ?, NULL, NULL, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        ON CONFLICT DO NOTHING
                        """.trimIndent(),
                        employerId.value,
                        employeeId.value,
                        event.orderId,
                        event.withheld.amount,
                        event.checkDate,
                        event.paycheckId,
                        event.payRunId,
                    )
                } else {
                    try {
                        jdbcTemplate.update(
                            """
                            INSERT INTO garnishment_ledger (
                              employer_id, employee_id, order_id,
                              total_withheld_cents, initial_arrears_cents, remaining_arrears_cents,
                              last_check_date, last_paycheck_id, last_pay_run_id,
                              created_at, updated_at
                            ) VALUES (?, ?, ?, ?, NULL, NULL, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                            """.trimIndent(),
                            employerId.value,
                            employeeId.value,
                            event.orderId,
                            event.withheld.amount,
                            event.checkDate,
                            event.paycheckId,
                            event.payRunId,
                        )
                    } catch (_: DataIntegrityViolationException) {
                        0
                    }
                }

                // Race: another writer inserted the ledger row after our UPDATE found nothing.
                // In that case we must apply our increment via UPDATE.
                if (insertedLedgerRows == 0) {
                    jdbcTemplate.update(
                        """
                        UPDATE garnishment_ledger
                        SET total_withheld_cents = total_withheld_cents + ?,
                            last_check_date = ?,
                            last_paycheck_id = ?,
                            last_pay_run_id = ?,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE employer_id = ?
                          AND employee_id = ?
                          AND order_id = ?
                        """.trimIndent(),
                        event.withheld.amount,
                        event.checkDate,
                        event.paycheckId,
                        event.payRunId,
                        employerId.value,
                        employeeId.value,
                        event.orderId,
                    )
                }
            }
        }
    }

    override fun findByEmployee(employerId: EmployerId, employeeId: EmployeeId): Map<String, GarnishmentLedgerEntry> {
        val sql =
            """
            SELECT employer_id, employee_id, order_id,
                   total_withheld_cents, initial_arrears_cents, remaining_arrears_cents,
                   last_check_date, last_paycheck_id, last_pay_run_id
            FROM garnishment_ledger
            WHERE employer_id = ?
              AND employee_id = ?
            """.trimIndent()

        val rows = jdbcTemplate.query(sql, LedgerRowMapper, employerId.value, employeeId.value)
        return rows.associateBy({ it.orderId }) { row ->
            GarnishmentLedgerEntry(
                employerId = EmployerId(row.employerId),
                employeeId = EmployeeId(row.employeeId),
                orderId = row.orderId,
                totalWithheld = Money(row.totalWithheldCents),
                initialArrears = row.initialArrearsCents?.let { Money(it) },
                remainingArrears = row.remainingArrearsCents?.let { Money(it) },
                lastCheckDate = row.lastCheckDate,
                lastPaycheckId = row.lastPaycheckId,
                lastPayRunId = row.lastPayRunId,
            )
        }
    }

    private data class LedgerRow(
        val employerId: String,
        val employeeId: String,
        val orderId: String,
        val totalWithheldCents: Long,
        val initialArrearsCents: Long?,
        val remainingArrearsCents: Long?,
        val lastCheckDate: LocalDate?,
        val lastPaycheckId: String?,
        val lastPayRunId: String?,
    )

    private object LedgerRowMapper : RowMapper<LedgerRow> {
        override fun mapRow(rs: ResultSet, rowNum: Int): LedgerRow = LedgerRow(
            employerId = rs.getString("employer_id"),
            employeeId = rs.getString("employee_id"),
            orderId = rs.getString("order_id"),
            totalWithheldCents = rs.getLong("total_withheld_cents"),
            initialArrearsCents = rs.getObject("initial_arrears_cents")?.let { rs.getLong("initial_arrears_cents") },
            remainingArrearsCents = rs.getObject("remaining_arrears_cents")?.let { rs.getLong("remaining_arrears_cents") },
            lastCheckDate = rs.getDate("last_check_date")?.toLocalDate(),
            lastPaycheckId = rs.getString("last_paycheck_id"),
            lastPayRunId = rs.getString("last_pay_run_id"),
        )
    }
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

/**
 * In-memory implementation used primarily for tests and development. The
 * production path should prefer [JdbcGarnishmentLedgerRepository].
 */
class InMemoryGarnishmentLedgerRepository : GarnishmentLedgerRepository {

    private data class Key(val employerId: String, val employeeId: String, val orderId: String)

    private val store = ConcurrentHashMap<Key, GarnishmentLedgerEntry>()

    override fun recordWithholdings(employerId: EmployerId, employeeId: EmployeeId, events: List<GarnishmentWithholdingEventView>) {
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

    override fun findByEmployee(employerId: EmployerId, employeeId: EmployeeId): Map<String, GarnishmentLedgerEntry> = store.filterKeys { it.employerId == employerId.value && it.employeeId == employeeId.value }
        .mapKeys { (key, _) -> key.orderId }
}
