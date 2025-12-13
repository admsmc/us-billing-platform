package com.example.uspayroll.hr.garnishment

import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

/**
 * Reconciles persisted garnishment orders against the arrears ledger.
 *
 * For each (employer, employee, order), we compute remaining arrears as
 *   remaining_arrears_cents = initial_arrears_cents - total_withheld_cents
 * using the garnishment_ledger aggregate, then update garnishment_order
 * current_arrears_cents and, when remaining <= 0, mark the order COMPLETED.
 */
@Service
class GarnishmentReconciliationService(
    private val jdbcTemplate: JdbcTemplate,
) {

    private val logger = LoggerFactory.getLogger(GarnishmentReconciliationService::class.java)

    fun reconcileForEmployee(employerId: EmployerId, employeeId: EmployeeId) {
        // Join garnishment_order with garnishment_ledger on order_id for this
        // employer/employee. We only reconcile orders that have an initial
        // arrears value; others are treated as "no arrears tracked".
        val sql =
            """
            SELECT o.order_id,
                   o.initial_arrears_cents,
                   COALESCE(l.total_withheld_cents, 0) AS total_withheld_cents,
                   o.served_date,
                   l.last_check_date
            FROM garnishment_order o
            LEFT JOIN garnishment_ledger l
              ON l.employer_id = o.employer_id
             AND l.employee_id = o.employee_id
             AND l.order_id = o.order_id
            WHERE o.employer_id = ?
              AND o.employee_id = ?
              AND o.initial_arrears_cents IS NOT NULL
            """.trimIndent()

        val rows = jdbcTemplate.query(
            sql,
            { rs, _ ->
                GarnishmentReconciliationRow(
                    orderId = rs.getString("order_id"),
                    initialArrearsCents = rs.getLong("initial_arrears_cents"),
                    totalWithheldCents = rs.getLong("total_withheld_cents"),
                    servedDate = rs.getDate("served_date")?.toLocalDate(),
                    lastCheckDate = rs.getDate("last_check_date")?.toLocalDate(),
                )
            },
            employerId.value,
            employeeId.value,
        )

        rows.forEach { row ->
            val remaining = row.initialArrearsCents - row.totalWithheldCents

            val arrearsAtLeast12Weeks = if (remaining > 0L && row.servedDate != null && row.lastCheckDate != null) {
                java.time.temporal.ChronoUnit.DAYS.between(row.servedDate, row.lastCheckDate) >= 84
            } else {
                false
            }

            // Update garnishment_order.current_arrears_cents and status, and
            // derive the arrears_at_least_12_weeks support flag from the
            // combination of served_date and last_check_date.
            val status = if (remaining <= 0L) OrderStatus.COMPLETED.name else OrderStatus.ACTIVE.name

            // Optional tightening: do not "resurrect" COMPLETED orders back to ACTIVE.
            // If remaining <= 0, the update is idempotent and allowed even if already COMPLETED.
            jdbcTemplate.update(
                """
                UPDATE garnishment_order
                   SET current_arrears_cents = ?,
                       status = ?,
                       arrears_at_least_12_weeks = ?,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE employer_id = ?
                   AND employee_id = ?
                   AND order_id = ?
                   AND (status <> 'COMPLETED' OR ? = 'COMPLETED')
                """.trimIndent(),
                remaining.coerceAtLeast(0L),
                status,
                arrearsAtLeast12Weeks,
                employerId.value,
                employeeId.value,
                row.orderId,
                status,
            )

            // Also update ledger.remaining_arrears_cents for debugging/ops,
            // when a ledger row exists.
            jdbcTemplate.update(
                """
                UPDATE garnishment_ledger
                   SET remaining_arrears_cents = ?,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE employer_id = ?
                   AND employee_id = ?
                   AND order_id = ?
                """.trimIndent(),
                remaining,
                employerId.value,
                employeeId.value,
                row.orderId,
            )

            logger.info(
                "garnishment_reconciliation.applied employer={} employee={} order_id={} initial_arrears_cents={} total_withheld_cents={} remaining_arrears_cents={} status=",
                employerId.value,
                employeeId.value,
                row.orderId,
                row.initialArrearsCents,
                row.totalWithheldCents,
                remaining,
                status,
            )
        }
    }
}

private data class GarnishmentReconciliationRow(
    val orderId: String,
    val initialArrearsCents: Long,
    val totalWithheldCents: Long,
    val servedDate: java.time.LocalDate?,
    val lastCheckDate: java.time.LocalDate?,
)
