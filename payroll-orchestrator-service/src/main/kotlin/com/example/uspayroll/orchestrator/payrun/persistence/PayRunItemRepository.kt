package com.example.uspayroll.orchestrator.payrun.persistence

import com.example.uspayroll.orchestrator.payrun.model.PayRunItemRecord
import com.example.uspayroll.orchestrator.payrun.model.PayRunItemStatus
import com.example.uspayroll.orchestrator.payrun.model.PayRunStatusCounts
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class PayRunItemRepository(
    private val jdbcTemplate: JdbcTemplate,
) {

    data class ClaimItemResult(
        val item: PayRunItemRecord,
        /** True if we transitioned QUEUED -> RUNNING in this call. */
        val claimed: Boolean,
    )

    private val supportsSkipLocked: Boolean by lazy {
        val ds = jdbcTemplate.dataSource ?: return@lazy false
        ds.connection.use { conn ->
            conn.metaData.databaseProductName.lowercase().contains("postgres")
        }
    }

    fun upsertQueuedItems(employerId: String, payRunId: String, employeeIds: List<String>) {
        // Do not overwrite existing rows (especially paycheck_id) on retries.
        // Use ON CONFLICT DO NOTHING to avoid Postgres transaction aborts inside larger
        // @Transactional flows.
        val distinct = employeeIds.distinct()
        if (distinct.isEmpty()) return

        // PostgreSQL prepared statement parameter limit: 65,535
        // Each row uses 4 parameters (employer_id, pay_run_id, employee_id, status).
        // Use 16,000 as max (16000 * 4 = 64000 < 65535)
        val maxRowsPerBatch = 16_000

        distinct.chunked(maxRowsPerBatch).forEach { batch ->
            // Build VALUES clause with placeholders
            val valuesClause = batch.joinToString(",") { "(?, ?, ?, ?, NULL, 0, NULL, NULL, NULL, CURRENT_TIMESTAMP)" }

            // Flatten parameters: each row contributes 4 params
            val params = batch.flatMap { employeeId ->
                listOf(employerId, payRunId, employeeId, PayRunItemStatus.QUEUED.name)
            }

            jdbcTemplate.update(
                """
                    INSERT INTO pay_run_item (
                      employer_id, pay_run_id, employee_id,
                      status, paycheck_id,
                      attempt_count, last_error,
                      started_at, completed_at, updated_at
                    ) VALUES $valuesClause
                    ON CONFLICT DO NOTHING
                """.trimIndent(),
                *params.toTypedArray(),
            )
        }
    }

    /**
     * Returns an already-assigned paycheck_id for the item, or assigns a new one.
     */
    fun findItem(employerId: String, payRunId: String, employeeId: String): PayRunItemRecord? = jdbcTemplate.query(
        """
            SELECT employer_id, pay_run_id, employee_id, status, paycheck_id, attempt_count, last_error, earning_overrides_json
            FROM pay_run_item
            WHERE employer_id = ? AND pay_run_id = ? AND employee_id = ?
        """.trimIndent(),
        { rs, _ ->
            PayRunItemRecord(
                employerId = rs.getString("employer_id"),
                payRunId = rs.getString("pay_run_id"),
                employeeId = rs.getString("employee_id"),
                status = PayRunItemStatus.valueOf(rs.getString("status")),
                paycheckId = rs.getString("paycheck_id"),
                attemptCount = rs.getInt("attempt_count"),
                lastError = rs.getString("last_error"),
                earningOverridesJson = rs.getString("earning_overrides_json"),
            )
        },
        employerId,
        payRunId,
        employeeId,
    ).firstOrNull()

    /**
     * Claim exactly one employee item for execution.
     *
     * If the item is QUEUED, transitions it to RUNNING and increments attempt_count.
     * If the item is RUNNING but stale (updated_at older than cutoff), requeues it.
     * Otherwise, returns the current state without claiming.
     */
    @Transactional
    fun claimItem(employerId: String, payRunId: String, employeeId: String, requeueStaleMillis: Long, now: Instant = Instant.now()): ClaimItemResult? {
        // Lock the row.
        val row = jdbcTemplate.query(
            """
            SELECT employer_id, pay_run_id, employee_id, status, paycheck_id, attempt_count, last_error, earning_overrides_json, updated_at
            FROM pay_run_item
            WHERE employer_id = ? AND pay_run_id = ? AND employee_id = ?
            FOR UPDATE
            """.trimIndent(),
            { rs, _ ->
                val updatedAt = rs.getTimestamp("updated_at")
                mapOf(
                    "employerId" to rs.getString("employer_id"),
                    "payRunId" to rs.getString("pay_run_id"),
                    "employeeId" to rs.getString("employee_id"),
                    "status" to rs.getString("status"),
                    "paycheckId" to rs.getString("paycheck_id"),
                    "attemptCount" to rs.getInt("attempt_count"),
                    "lastError" to rs.getString("last_error"),
                    "earningOverridesJson" to rs.getString("earning_overrides_json"),
                    "updatedAt" to updatedAt,
                )
            },
            employerId,
            payRunId,
            employeeId,
        ).firstOrNull() ?: return null

        val status = PayRunItemStatus.valueOf(row["status"] as String)
        val attemptCount = row["attemptCount"] as Int
        val paycheckId = row["paycheckId"] as String?
        val lastError = row["lastError"] as String?
        val updatedAt = row["updatedAt"] as Timestamp?

        val staleCutoff = now.minusMillis(requeueStaleMillis.coerceAtLeast(0L))
        val isStaleRunning = status == PayRunItemStatus.RUNNING && updatedAt != null && updatedAt.toInstant().isBefore(staleCutoff)

        if (isStaleRunning) {
            requeueStaleRunningItems(
                employerId = employerId,
                payRunId = payRunId,
                cutoff = staleCutoff,
                reason = "requeued_stale_running cutoffMs=$requeueStaleMillis",
            )
        }

        // Re-read the current status after potential requeue.
        val current = findItem(employerId, payRunId, employeeId) ?: return null

        if (current.status != PayRunItemStatus.QUEUED) {
            return ClaimItemResult(item = current, claimed = false)
        }

        // Claim QUEUED -> RUNNING.
        jdbcTemplate.update(
            """
            UPDATE pay_run_item
            SET status = ?,
                attempt_count = attempt_count + 1,
                started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
                updated_at = CURRENT_TIMESTAMP
            WHERE employer_id = ? AND pay_run_id = ? AND employee_id = ? AND status = ?
            """.trimIndent(),
            PayRunItemStatus.RUNNING.name,
            employerId,
            payRunId,
            employeeId,
            PayRunItemStatus.QUEUED.name,
        )

        val claimedRow = findItem(employerId, payRunId, employeeId) ?: return null
        return ClaimItemResult(item = claimedRow, claimed = true)
    }

    /**
     * Attach explicit earning overrides to an item if not already set.
     *
     * This is intentionally non-destructive to preserve idempotent start-finalize semantics.
     */
    fun setEarningOverridesIfAbsent(employerId: String, payRunId: String, employeeId: String, earningOverridesJson: String): Boolean {
        val updated = jdbcTemplate.update(
            """
            UPDATE pay_run_item
            SET earning_overrides_json = ?, updated_at = CURRENT_TIMESTAMP
            WHERE employer_id = ? AND pay_run_id = ? AND employee_id = ?
              AND earning_overrides_json IS NULL
            """.trimIndent(),
            earningOverridesJson,
            employerId,
            payRunId,
            employeeId,
        )
        return updated == 1
    }

    fun setEarningOverridesIfAbsentBatch(employerId: String, payRunId: String, earningOverridesByEmployeeId: Map<String, String>) {
        if (earningOverridesByEmployeeId.isEmpty()) return

        // Each UPDATE uses 4 parameters (json, employer_id, pay_run_id, employee_id)
        val maxRowsPerBatch = 10_000

        earningOverridesByEmployeeId.entries.chunked(maxRowsPerBatch).forEach { batch ->
            jdbcTemplate.batchUpdate(
                """
                UPDATE pay_run_item
                SET earning_overrides_json = ?, updated_at = CURRENT_TIMESTAMP
                WHERE employer_id = ? AND pay_run_id = ? AND employee_id = ?
                  AND earning_overrides_json IS NULL
                """.trimIndent(),
                batch.map { (employeeId, json) ->
                    arrayOf(
                        json,
                        employerId,
                        payRunId,
                        employeeId,
                    )
                },
            )
        }
    }

    fun getOrAssignPaycheckId(employerId: String, payRunId: String, employeeId: String): String {
        val existing = jdbcTemplate.query(
            """
            SELECT paycheck_id
            FROM pay_run_item
            WHERE employer_id = ? AND pay_run_id = ? AND employee_id = ?
            """.trimIndent(),
            { rs, _ -> rs.getString("paycheck_id") },
            employerId,
            payRunId,
            employeeId,
        ).firstOrNull()

        if (!existing.isNullOrBlank()) return existing

        val newId = "chk-${UUID.randomUUID()}"
        val updated = jdbcTemplate.update(
            """
            UPDATE pay_run_item
            SET paycheck_id = ?, updated_at = CURRENT_TIMESTAMP
            WHERE employer_id = ? AND pay_run_id = ? AND employee_id = ?
              AND paycheck_id IS NULL
            """.trimIndent(),
            newId,
            employerId,
            payRunId,
            employeeId,
        )

        if (updated == 1) return newId

        return jdbcTemplate.query(
            """
            SELECT paycheck_id
            FROM pay_run_item
            WHERE employer_id = ? AND pay_run_id = ? AND employee_id = ?
            """.trimIndent(),
            { rs, _ -> rs.getString("paycheck_id") },
            employerId,
            payRunId,
            employeeId,
        ).firstOrNull() ?: newId
    }

    /**
     * Assign paycheck IDs for items that don't have one yet.
     *
     * This is intended for queue-driven payruns so job payloads can include
     * paycheckId without requiring orchestration-time generation.
     */
    fun assignPaycheckIdsIfAbsentBatch(employerId: String, payRunId: String, paycheckIdsByEmployeeId: Map<String, String>) {
        if (paycheckIdsByEmployeeId.isEmpty()) return

        // Each UPDATE uses 4 parameters (paycheck_id, employer_id, pay_run_id, employee_id)
        val maxRowsPerBatch = 10_000

        paycheckIdsByEmployeeId.entries.chunked(maxRowsPerBatch).forEach { batch ->
            jdbcTemplate.batchUpdate(
                """
                UPDATE pay_run_item
                SET paycheck_id = ?, updated_at = CURRENT_TIMESTAMP
                WHERE employer_id = ? AND pay_run_id = ? AND employee_id = ?
                  AND paycheck_id IS NULL
                """.trimIndent(),
                batch.map { (employeeId, paycheckId) ->
                    arrayOf(
                        paycheckId,
                        employerId,
                        payRunId,
                        employeeId,
                    )
                },
            )
        }
    }

    fun findPaycheckIds(employerId: String, payRunId: String, employeeIds: List<String>): Map<String, String> {
        val distinct = employeeIds.distinct()
        if (distinct.isEmpty()) return emptyMap()

        // PostgreSQL parameter limit: 65,535
        // Each query uses 2 base params + N employee IDs, so max ~65K IDs per batch
        val maxIdsPerBatch = 60_000

        val allRows = distinct.chunked(maxIdsPerBatch).flatMap { batch ->
            val placeholders = batch.joinToString(",") { "?" }

            jdbcTemplate.query(
                """
                SELECT employee_id, paycheck_id
                FROM pay_run_item
                WHERE employer_id = ? AND pay_run_id = ?
                  AND employee_id IN ($placeholders)
                """.trimIndent(),
                { ps ->
                    var i = 1
                    ps.setString(i++, employerId)
                    ps.setString(i++, payRunId)
                    batch.forEach { id -> ps.setString(i++, id) }
                },
            ) { rs, _ ->
                rs.getString("employee_id") to rs.getString("paycheck_id")
            }
        }

        return allRows
            .filter { (_, paycheckId) -> !paycheckId.isNullOrBlank() }
            .associate { (employeeId, paycheckId) -> employeeId to paycheckId }
    }

    fun findEarningOverridesJson(employerId: String, payRunId: String, employeeIds: List<String>): Map<String, String> {
        val distinct = employeeIds.distinct()
        if (distinct.isEmpty()) return emptyMap()

        // PostgreSQL parameter limit: 65,535
        // Each query uses 2 base params + N employee IDs, so max ~65K IDs per batch
        val maxIdsPerBatch = 60_000

        val allRows = distinct.chunked(maxIdsPerBatch).flatMap { batch ->
            val placeholders = batch.joinToString(",") { "?" }

            jdbcTemplate.query(
                """
                SELECT employee_id, earning_overrides_json
                FROM pay_run_item
                WHERE employer_id = ? AND pay_run_id = ?
                  AND employee_id IN ($placeholders)
                """.trimIndent(),
                { ps ->
                    var i = 1
                    ps.setString(i++, employerId)
                    ps.setString(i++, payRunId)
                    batch.forEach { id -> ps.setString(i++, id) }
                },
            ) { rs, _ ->
                rs.getString("employee_id") to rs.getString("earning_overrides_json")
            }
        }

        return allRows
            .filter { (_, json) -> !json.isNullOrBlank() }
            .associate { (employeeId, json) -> employeeId to json }
    }

    /**
     * Atomically claims up to [batchSize] QUEUED items by selecting them with
     * row locks and updating their status within a single transaction.
     */
    @Transactional
    fun claimQueuedItems(employerId: String, payRunId: String, batchSize: Int): List<String> {
        if (batchSize <= 0) return emptyList()

        // Row-lock the candidate set. This makes concurrent claimers block
        // rather than double-claiming the same employees.
        val lockClause = if (supportsSkipLocked) "FOR UPDATE SKIP LOCKED" else "FOR UPDATE"
        val ids = jdbcTemplate.query(
            """
            SELECT employee_id
            FROM pay_run_item
            WHERE employer_id = ? AND pay_run_id = ? AND status = ?
            ORDER BY employee_id
            LIMIT ?
            $lockClause
            """.trimIndent(),
            { rs, _ -> rs.getString("employee_id") },
            employerId,
            payRunId,
            PayRunItemStatus.QUEUED.name,
            batchSize,
        )

        if (ids.isEmpty()) return emptyList()

        val placeholders = ids.joinToString(",") { "?" }
        val args: MutableList<Any> = mutableListOf(
            PayRunItemStatus.RUNNING.name,
            employerId,
            payRunId,
            PayRunItemStatus.QUEUED.name,
        )
        args.addAll(ids)

        val updatedRows = jdbcTemplate.update(
            """
            UPDATE pay_run_item
            SET status = ?,
                attempt_count = attempt_count + 1,
                started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
                updated_at = CURRENT_TIMESTAMP
            WHERE employer_id = ?
              AND pay_run_id = ?
              AND status = ?
              AND employee_id IN ($placeholders)
            """.trimIndent(),
            *args.toTypedArray(),
        )

        // In a properly locked transaction this should be exact; if not, only
        // return the subset that are now RUNNING.
        return if (updatedRows == ids.size) {
            ids
        } else {
            jdbcTemplate.query(
                """
                SELECT employee_id
                FROM pay_run_item
                WHERE employer_id = ? AND pay_run_id = ? AND status = ?
                  AND employee_id IN ($placeholders)
                """.trimIndent(),
                { rs, _ -> rs.getString("employee_id") },
                employerId,
                payRunId,
                PayRunItemStatus.RUNNING.name,
                *ids.toTypedArray(),
            )
        }
    }

    fun markSucceeded(employerId: String, payRunId: String, employeeId: String, paycheckId: String) {
        // Only transition RUNNING -> SUCCEEDED. This prevents stale retries from overwriting
        // terminal states.
        jdbcTemplate.update(
            """
            UPDATE pay_run_item
            SET status = ?,
                paycheck_id = ?,
                last_error = NULL,
                completed_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE employer_id = ? AND pay_run_id = ? AND employee_id = ?
              AND status = ?
            """.trimIndent(),
            PayRunItemStatus.SUCCEEDED.name,
            paycheckId,
            employerId,
            payRunId,
            employeeId,
            PayRunItemStatus.RUNNING.name,
        )
    }

    /**
     * Record a retryable failure and requeue the item (status=QUEUED).
     *
     * attempt_count should already have been incremented by claimItem().
     */
    fun markRetryableFailure(employerId: String, payRunId: String, employeeId: String, error: String) {
        val truncated = if (error.length <= 2000) error else error.take(2000)

        // Only requeue RUNNING -> QUEUED.
        jdbcTemplate.update(
            """
            UPDATE pay_run_item
            SET status = ?,
                last_error = ?,
                started_at = NULL,
                completed_at = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE employer_id = ? AND pay_run_id = ? AND employee_id = ?
              AND status = ?
            """.trimIndent(),
            PayRunItemStatus.QUEUED.name,
            truncated,
            employerId,
            payRunId,
            employeeId,
            PayRunItemStatus.RUNNING.name,
        )
    }

    fun markFailed(employerId: String, payRunId: String, employeeId: String, error: String) {
        val truncated = if (error.length <= 2000) error else error.take(2000)

        // Only transition RUNNING -> FAILED.
        jdbcTemplate.update(
            """
            UPDATE pay_run_item
            SET status = ?,
                last_error = ?,
                completed_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE employer_id = ? AND pay_run_id = ? AND employee_id = ?
              AND status = ?
            """.trimIndent(),
            PayRunItemStatus.FAILED.name,
            truncated,
            employerId,
            payRunId,
            employeeId,
            PayRunItemStatus.RUNNING.name,
        )
    }

    /**
     * Requeues items that appear stuck in RUNNING (e.g. worker crash mid-item).
     *
     * We treat an item as stale when its [updated_at] timestamp is older than
     * [cutoff]. This is conservative: normal execution should keep updating
     * rows as they transition to SUCCEEDED/FAILED.
     */
    fun requeueStaleRunningItems(employerId: String, payRunId: String, cutoff: Instant, reason: String = "requeued_stale_running"): Int {
        val cutoffTs = Timestamp.from(cutoff)
        val msg = if (reason.length <= 2000) reason else reason.take(2000)

        return jdbcTemplate.update(
            """
            UPDATE pay_run_item
            SET status = ?,
                last_error = CASE
                    WHEN last_error IS NULL THEN ?
                    ELSE CONCAT(last_error, '; ', ?)
                END,
                started_at = NULL,
                completed_at = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE employer_id = ?
              AND pay_run_id = ?
              AND status = ?
              AND completed_at IS NULL
              AND updated_at < ?
            """.trimIndent(),
            PayRunItemStatus.QUEUED.name,
            msg,
            msg,
            employerId,
            payRunId,
            PayRunItemStatus.RUNNING.name,
            cutoffTs,
        )
    }

    fun countsForPayRun(employerId: String, payRunId: String): PayRunStatusCounts {
        val row = jdbcTemplate.query(
            """
            SELECT
              COUNT(*) AS total,
              SUM(CASE WHEN status = ? THEN 1 ELSE 0 END) AS queued,
              SUM(CASE WHEN status = ? THEN 1 ELSE 0 END) AS running,
              SUM(CASE WHEN status = ? THEN 1 ELSE 0 END) AS succeeded,
              SUM(CASE WHEN status = ? THEN 1 ELSE 0 END) AS failed
            FROM pay_run_item
            WHERE employer_id = ? AND pay_run_id = ?
            """.trimIndent(),
            { rs, _ ->
                PayRunStatusCounts(
                    total = rs.getInt("total"),
                    queued = rs.getInt("queued"),
                    running = rs.getInt("running"),
                    succeeded = rs.getInt("succeeded"),
                    failed = rs.getInt("failed"),
                )
            },
            PayRunItemStatus.QUEUED.name,
            PayRunItemStatus.RUNNING.name,
            PayRunItemStatus.SUCCEEDED.name,
            PayRunItemStatus.FAILED.name,
            employerId,
            payRunId,
        ).firstOrNull()

        return row ?: PayRunStatusCounts(total = 0, queued = 0, running = 0, succeeded = 0, failed = 0)
    }

    fun hasAnyQueuedOrRunning(employerId: String, payRunId: String): Boolean {
        val n = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM pay_run_item
            WHERE employer_id = ? AND pay_run_id = ? AND status IN (?, ?)
            """.trimIndent(),
            Long::class.java,
            employerId,
            payRunId,
            PayRunItemStatus.QUEUED.name,
            PayRunItemStatus.RUNNING.name,
        )
        return (n ?: 0L) > 0L
    }

    fun listFailedItems(employerId: String, payRunId: String, limit: Int = 50): List<Pair<String, String?>> {
        val effectiveLimit = limit.coerceIn(1, 500)
        return jdbcTemplate.query(
            """
            SELECT employee_id, last_error
            FROM pay_run_item
            WHERE employer_id = ? AND pay_run_id = ? AND status = ?
            ORDER BY employee_id
            LIMIT ?
            """.trimIndent(),
            { rs, _ -> rs.getString("employee_id") to rs.getString("last_error") },
            employerId,
            payRunId,
            PayRunItemStatus.FAILED.name,
            effectiveLimit,
        )
    }

    fun listSucceededPaychecks(employerId: String, payRunId: String, limit: Int = 10_000): List<Pair<String, String>> {
        val effectiveLimit = limit.coerceIn(1, 100_000)
        return jdbcTemplate.query(
            """
            SELECT employee_id, paycheck_id
            FROM pay_run_item
            WHERE employer_id = ? AND pay_run_id = ? AND status = ? AND paycheck_id IS NOT NULL
            ORDER BY employee_id
            LIMIT ?
            """.trimIndent(),
            { rs, _ -> rs.getString("employee_id") to rs.getString("paycheck_id") },
            employerId,
            payRunId,
            PayRunItemStatus.SUCCEEDED.name,
            effectiveLimit,
        )
    }

    fun listEmployeeIdsByStatus(employerId: String, payRunId: String, status: PayRunItemStatus, limit: Int = 10_000): List<String> {
        val effectiveLimit = limit.coerceIn(1, 100_000)
        return jdbcTemplate.query(
            """
            SELECT employee_id
            FROM pay_run_item
            WHERE employer_id = ? AND pay_run_id = ? AND status = ?
            ORDER BY employee_id
            LIMIT ?
            """.trimIndent(),
            { rs, _ -> rs.getString("employee_id") },
            employerId,
            payRunId,
            status.name,
            effectiveLimit,
        )
    }

    /**
     * Operator-triggered: move FAILED -> QUEUED so the item can be retried.
     *
     * This does not reset attempt_count; attempt_count reflects historical attempts.
     */
    fun requeueFailedItems(employerId: String, payRunId: String, employeeIds: List<String>, reason: String): Int {
        val distinct = employeeIds.distinct()
        if (distinct.isEmpty()) return 0

        val msg = if (reason.length <= 2000) reason else reason.take(2000)
        val placeholders = distinct.joinToString(",") { "?" }

        val sql =
            """
            UPDATE pay_run_item
            SET status = ?,
                last_error = CASE
                    WHEN last_error IS NULL THEN ?
                    ELSE CONCAT(last_error, '; ', ?)
                END,
                started_at = NULL,
                completed_at = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE employer_id = ?
              AND pay_run_id = ?
              AND status = ?
              AND employee_id IN ($placeholders)
            """.trimIndent()

        return jdbcTemplate.update(sql) { ps ->
            var i = 1
            ps.setString(i++, PayRunItemStatus.QUEUED.name)
            ps.setString(i++, msg)
            ps.setString(i++, msg)
            ps.setString(i++, employerId)
            ps.setString(i++, payRunId)
            ps.setString(i++, PayRunItemStatus.FAILED.name)
            distinct.forEach { employeeId ->
                ps.setString(i++, employeeId)
            }
        }
    }
}
