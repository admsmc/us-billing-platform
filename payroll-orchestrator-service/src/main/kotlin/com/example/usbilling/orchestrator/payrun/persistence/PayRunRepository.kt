package com.example.usbilling.orchestrator.payrun.persistence

import com.example.usbilling.orchestrator.payrun.model.PayRunRecord
import com.example.usbilling.orchestrator.payrun.model.PayRunStatus
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.Instant

@Repository
class PayRunRepository(
    private val jdbcTemplate: JdbcTemplate,
) {

    data class CreateOrGetPayRunResult(
        val payRun: PayRunRecord,
        val wasCreated: Boolean,
    )

    fun listPayRunsByStatus(employerId: String, status: PayRunStatus, limit: Int = 100): List<PayRunRecord> {
        val effectiveLimit = limit.coerceIn(1, 500)
        return jdbcTemplate.query(
            """
            SELECT employer_id, pay_run_id, pay_period_id, run_type, run_sequence, status,
                   approval_status, payment_status,
                   requested_idempotency_key,
                   lease_owner, lease_expires_at_epoch_ms,
                   finalize_started_at, finalize_completed_at
            FROM pay_run
            WHERE employer_id = ? AND status = ?
            ORDER BY updated_at
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                val leaseEpoch = rs.getLong("lease_expires_at_epoch_ms")
                val leaseInstant = if (rs.wasNull()) null else Instant.ofEpochMilli(leaseEpoch)

                val finalizeStartedAt = rs.getTimestamp("finalize_started_at")?.toInstant()
                val finalizeCompletedAt = rs.getTimestamp("finalize_completed_at")?.toInstant()

                PayRunRecord(
                    employerId = rs.getString("employer_id"),
                    payRunId = rs.getString("pay_run_id"),
                    payPeriodId = rs.getString("pay_period_id"),
                    runType = com.example.usbilling.orchestrator.payrun.model.PayRunType.valueOf(rs.getString("run_type")),
                    runSequence = rs.getInt("run_sequence"),
                    status = PayRunStatus.valueOf(rs.getString("status")),
                    approvalStatus = com.example.usbilling.orchestrator.payrun.model.ApprovalStatus.valueOf(rs.getString("approval_status")),
                    paymentStatus = com.example.usbilling.orchestrator.payrun.model.PaymentStatus.valueOf(rs.getString("payment_status")),
                    requestedIdempotencyKey = rs.getString("requested_idempotency_key"),
                    leaseOwner = rs.getString("lease_owner"),
                    leaseExpiresAt = leaseInstant,
                    finalizeStartedAt = finalizeStartedAt,
                    finalizeCompletedAt = finalizeCompletedAt,
                )
            },
            employerId,
            status.name,
            effectiveLimit,
        )
    }

    fun listPayRunsByStatus(status: PayRunStatus, limit: Int = 100): List<PayRunRecord> {
        val effectiveLimit = limit.coerceIn(1, 500)
        return jdbcTemplate.query(
            """
            SELECT employer_id, pay_run_id, pay_period_id, run_type, run_sequence, status,
                   approval_status, payment_status,
                   requested_idempotency_key,
                   lease_owner, lease_expires_at_epoch_ms,
                   finalize_started_at, finalize_completed_at
            FROM pay_run
            WHERE status = ?
            ORDER BY updated_at
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                val leaseEpoch = rs.getLong("lease_expires_at_epoch_ms")
                val leaseInstant = if (rs.wasNull()) null else Instant.ofEpochMilli(leaseEpoch)

                val finalizeStartedAt = rs.getTimestamp("finalize_started_at")?.toInstant()
                val finalizeCompletedAt = rs.getTimestamp("finalize_completed_at")?.toInstant()

                PayRunRecord(
                    employerId = rs.getString("employer_id"),
                    payRunId = rs.getString("pay_run_id"),
                    payPeriodId = rs.getString("pay_period_id"),
                    runType = com.example.usbilling.orchestrator.payrun.model.PayRunType.valueOf(rs.getString("run_type")),
                    runSequence = rs.getInt("run_sequence"),
                    status = PayRunStatus.valueOf(rs.getString("status")),
                    approvalStatus = com.example.usbilling.orchestrator.payrun.model.ApprovalStatus.valueOf(rs.getString("approval_status")),
                    paymentStatus = com.example.usbilling.orchestrator.payrun.model.PaymentStatus.valueOf(rs.getString("payment_status")),
                    requestedIdempotencyKey = rs.getString("requested_idempotency_key"),
                    leaseOwner = rs.getString("lease_owner"),
                    leaseExpiresAt = leaseInstant,
                    finalizeStartedAt = finalizeStartedAt,
                    finalizeCompletedAt = finalizeCompletedAt,
                )
            },
            status.name,
            effectiveLimit,
        )
    }

    fun createOrGetPayRun(
        employerId: String,
        payRunId: String,
        payPeriodId: String,
        runType: com.example.usbilling.orchestrator.payrun.model.PayRunType,
        runSequence: Int,
        requestedIdempotencyKey: String?,
        initialStatus: PayRunStatus = PayRunStatus.QUEUED,
    ): CreateOrGetPayRunResult {
        // IMPORTANT: Use ON CONFLICT DO NOTHING so uniqueness collisions do not abort the
        // surrounding @Transactional payrun start flow (Postgres marks the transaction as aborted
        // after a constraint violation). We then look up the existing payrun by the relevant key.
        val insertedRows = jdbcTemplate.update(
            """
                INSERT INTO pay_run (
                  employer_id, pay_run_id, pay_period_id,
                  run_type, run_sequence,
                  status,
                  approval_status, payment_status,
                  requested_idempotency_key,
                  lease_owner, lease_expires_at_epoch_ms,
                  finalize_started_at, finalize_completed_at,
                  created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, CURRENT_TIMESTAMP, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT DO NOTHING
            """.trimIndent(),
            employerId,
            payRunId,
            payPeriodId,
            runType.name,
            runSequence,
            initialStatus.name,
            com.example.usbilling.orchestrator.payrun.model.ApprovalStatus.PENDING.name,
            com.example.usbilling.orchestrator.payrun.model.PaymentStatus.UNPAID.name,
            requestedIdempotencyKey,
        )

        if (insertedRows == 1) {
            val inserted = findPayRun(employerId, payRunId)
                ?: error("pay_run insert succeeded but row not found employer=$employerId payRunId=$payRunId")
            return CreateOrGetPayRunResult(payRun = inserted, wasCreated = true)
        }

        // Not inserted => return existing payrun (idempotent create semantics).
        if (requestedIdempotencyKey != null) {
            val existingByKey = findByIdempotencyKey(employerId, requestedIdempotencyKey)
            if (existingByKey != null) return CreateOrGetPayRunResult(payRun = existingByKey, wasCreated = false)
        }

        val existingByBusinessKey = findPayRunByBusinessKey(
            employerId = employerId,
            payPeriodId = payPeriodId,
            runType = runType,
            runSequence = runSequence,
        )
        if (existingByBusinessKey != null) return CreateOrGetPayRunResult(payRun = existingByBusinessKey, wasCreated = false)

        val existingById = findPayRun(employerId, payRunId)
        if (existingById != null) return CreateOrGetPayRunResult(payRun = existingById, wasCreated = false)

        throw DataIntegrityViolationException(
            "pay_run insert conflicted but existing row could not be found employer=$employerId payPeriodId=$payPeriodId runType=$runType runSequence=$runSequence payRunId=$payRunId",
        )
    }

    fun findPayRun(employerId: String, payRunId: String): PayRunRecord? = jdbcTemplate.query(
        """
            SELECT employer_id, pay_run_id, pay_period_id, run_type, run_sequence, status,
                   approval_status, payment_status,
                   requested_idempotency_key,
                   lease_owner, lease_expires_at_epoch_ms,
                   finalize_started_at, finalize_completed_at
            FROM pay_run
            WHERE employer_id = ? AND pay_run_id = ?
        """.trimIndent(),
        { rs, _ ->
            val leaseEpoch = rs.getLong("lease_expires_at_epoch_ms")
            val leaseInstant = if (rs.wasNull()) null else Instant.ofEpochMilli(leaseEpoch)

            val finalizeStartedAt = rs.getTimestamp("finalize_started_at")?.toInstant()
            val finalizeCompletedAt = rs.getTimestamp("finalize_completed_at")?.toInstant()

            PayRunRecord(
                employerId = rs.getString("employer_id"),
                payRunId = rs.getString("pay_run_id"),
                payPeriodId = rs.getString("pay_period_id"),
                runType = com.example.usbilling.orchestrator.payrun.model.PayRunType.valueOf(rs.getString("run_type")),
                runSequence = rs.getInt("run_sequence"),
                status = PayRunStatus.valueOf(rs.getString("status")),
                approvalStatus = com.example.usbilling.orchestrator.payrun.model.ApprovalStatus.valueOf(rs.getString("approval_status")),
                paymentStatus = com.example.usbilling.orchestrator.payrun.model.PaymentStatus.valueOf(rs.getString("payment_status")),
                requestedIdempotencyKey = rs.getString("requested_idempotency_key"),
                leaseOwner = rs.getString("lease_owner"),
                leaseExpiresAt = leaseInstant,
                finalizeStartedAt = finalizeStartedAt,
                finalizeCompletedAt = finalizeCompletedAt,
            )
        },
        employerId,
        payRunId,
    ).firstOrNull()

    fun findPayRunByBusinessKey(employerId: String, payPeriodId: String, runType: com.example.usbilling.orchestrator.payrun.model.PayRunType, runSequence: Int): PayRunRecord? = jdbcTemplate.query(
        """
            SELECT employer_id, pay_run_id, pay_period_id, run_type, run_sequence, status,
                   approval_status, payment_status,
                   requested_idempotency_key,
                   lease_owner, lease_expires_at_epoch_ms,
                   finalize_started_at, finalize_completed_at
            FROM pay_run
            WHERE employer_id = ? AND pay_period_id = ? AND run_type = ? AND run_sequence = ?
            LIMIT 1
        """.trimIndent(),
        { rs, _ ->
            val leaseEpoch = rs.getLong("lease_expires_at_epoch_ms")
            val leaseInstant = if (rs.wasNull()) null else Instant.ofEpochMilli(leaseEpoch)

            val finalizeStartedAt = rs.getTimestamp("finalize_started_at")?.toInstant()
            val finalizeCompletedAt = rs.getTimestamp("finalize_completed_at")?.toInstant()

            PayRunRecord(
                employerId = rs.getString("employer_id"),
                payRunId = rs.getString("pay_run_id"),
                payPeriodId = rs.getString("pay_period_id"),
                runType = com.example.usbilling.orchestrator.payrun.model.PayRunType.valueOf(rs.getString("run_type")),
                runSequence = rs.getInt("run_sequence"),
                status = PayRunStatus.valueOf(rs.getString("status")),
                approvalStatus = com.example.usbilling.orchestrator.payrun.model.ApprovalStatus.valueOf(rs.getString("approval_status")),
                paymentStatus = com.example.usbilling.orchestrator.payrun.model.PaymentStatus.valueOf(rs.getString("payment_status")),
                requestedIdempotencyKey = rs.getString("requested_idempotency_key"),
                leaseOwner = rs.getString("lease_owner"),
                leaseExpiresAt = leaseInstant,
                finalizeStartedAt = finalizeStartedAt,
                finalizeCompletedAt = finalizeCompletedAt,
            )
        },
        employerId,
        payPeriodId,
        runType.name,
        runSequence,
    ).firstOrNull()

    fun findByIdempotencyKey(employerId: String, requestedIdempotencyKey: String): PayRunRecord? = jdbcTemplate.query(
        """
            SELECT employer_id, pay_run_id, pay_period_id, run_type, run_sequence, status,
                   approval_status, payment_status,
                   requested_idempotency_key,
                   lease_owner, lease_expires_at_epoch_ms,
                   finalize_started_at, finalize_completed_at
            FROM pay_run
            WHERE employer_id = ? AND requested_idempotency_key = ?
        """.trimIndent(),
        { rs, _ ->
            val leaseEpoch = rs.getLong("lease_expires_at_epoch_ms")
            val leaseInstant = if (rs.wasNull()) null else Instant.ofEpochMilli(leaseEpoch)

            val finalizeStartedAt = rs.getTimestamp("finalize_started_at")?.toInstant()
            val finalizeCompletedAt = rs.getTimestamp("finalize_completed_at")?.toInstant()

            PayRunRecord(
                employerId = rs.getString("employer_id"),
                payRunId = rs.getString("pay_run_id"),
                payPeriodId = rs.getString("pay_period_id"),
                runType = com.example.usbilling.orchestrator.payrun.model.PayRunType.valueOf(rs.getString("run_type")),
                runSequence = rs.getInt("run_sequence"),
                status = PayRunStatus.valueOf(rs.getString("status")),
                approvalStatus = com.example.usbilling.orchestrator.payrun.model.ApprovalStatus.valueOf(rs.getString("approval_status")),
                paymentStatus = com.example.usbilling.orchestrator.payrun.model.PaymentStatus.valueOf(rs.getString("payment_status")),
                requestedIdempotencyKey = rs.getString("requested_idempotency_key"),
                leaseOwner = rs.getString("lease_owner"),
                leaseExpiresAt = leaseInstant,
                finalizeStartedAt = finalizeStartedAt,
                finalizeCompletedAt = finalizeCompletedAt,
            )
        },
        employerId,
        requestedIdempotencyKey,
    ).firstOrNull()

    /**
     * Acquire the lease if it is currently unowned/expired OR renew it if we already own it.
     *
     * This supports time-sliced execution where the same worker repeatedly calls /execute while
     * a payrun remains RUNNING.
     */
    fun acquireOrRenewLease(employerId: String, payRunId: String, leaseOwner: String, leaseDuration: Duration, now: Instant = Instant.now()): Boolean {
        val nowEpoch = now.toEpochMilli()
        val leaseUntilEpoch = now.plus(leaseDuration).toEpochMilli()

        val updated = jdbcTemplate.update(
            """
            UPDATE pay_run
            SET lease_owner = ?,
                lease_expires_at_epoch_ms = ?,
                status = CASE WHEN status = ? THEN ? ELSE status END,
                updated_at = CURRENT_TIMESTAMP
            WHERE employer_id = ?
              AND pay_run_id = ?
              AND status IN (?, ?)
              AND (
                (lease_owner = ? AND lease_expires_at_epoch_ms IS NOT NULL AND lease_expires_at_epoch_ms >= ?)
                OR (lease_expires_at_epoch_ms IS NULL OR lease_expires_at_epoch_ms < ?)
              )
            """.trimIndent(),
            leaseOwner,
            leaseUntilEpoch,
            PayRunStatus.QUEUED.name,
            PayRunStatus.RUNNING.name,
            employerId,
            payRunId,
            PayRunStatus.QUEUED.name,
            PayRunStatus.RUNNING.name,
            leaseOwner,
            nowEpoch,
            nowEpoch,
        )

        return updated == 1
    }

    /**
     * Heartbeat: extend the lease if the caller still owns it and it hasn't expired.
     */
    fun renewLeaseIfOwned(employerId: String, payRunId: String, leaseOwner: String, leaseDuration: Duration, now: Instant = Instant.now()): Boolean {
        val nowEpoch = now.toEpochMilli()
        val leaseUntilEpoch = now.plus(leaseDuration).toEpochMilli()

        val updated = jdbcTemplate.update(
            """
            UPDATE pay_run
            SET lease_expires_at_epoch_ms = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE employer_id = ?
              AND pay_run_id = ?
              AND lease_owner = ?
              AND lease_expires_at_epoch_ms IS NOT NULL
              AND lease_expires_at_epoch_ms >= ?
              AND status = ?
            """.trimIndent(),
            leaseUntilEpoch,
            employerId,
            payRunId,
            leaseOwner,
            nowEpoch,
            PayRunStatus.RUNNING.name,
        )

        return updated == 1
    }

    fun markRunningIfQueued(employerId: String, payRunId: String): Boolean {
        val updated = jdbcTemplate.update(
            """
            UPDATE pay_run
            SET status = ?,
                finalize_started_at = COALESCE(finalize_started_at, CURRENT_TIMESTAMP),
                updated_at = CURRENT_TIMESTAMP
            WHERE employer_id = ? AND pay_run_id = ? AND status IN (?, ?)
            """.trimIndent(),
            PayRunStatus.RUNNING.name,
            employerId,
            payRunId,
            PayRunStatus.QUEUED.name,
            PayRunStatus.PENDING.name,
        )
        return updated == 1
    }

    fun setFinalStatusAndReleaseLease(employerId: String, payRunId: String, status: PayRunStatus) {
        // Prevent stale workers/finalizers from overwriting an already-terminal payrun.
        jdbcTemplate.update(
            """
            UPDATE pay_run
            SET status = ?,
                lease_owner = NULL,
                lease_expires_at_epoch_ms = NULL,
                finalize_completed_at = COALESCE(finalize_completed_at, CURRENT_TIMESTAMP),
                updated_at = CURRENT_TIMESTAMP
            WHERE employer_id = ? AND pay_run_id = ?
              AND status IN (?, ?)
            """.trimIndent(),
            status.name,
            employerId,
            payRunId,
            PayRunStatus.QUEUED.name,
            PayRunStatus.RUNNING.name,
        )
    }

    /**
     * Best-effort: if the payrun has reached a terminal state (as *computed* from items),
     * we still want a stable server-side completion timestamp even if the scheduled
     * finalizer hasn't persisted a terminal status yet.
     */
    fun markFinalizeCompletedIfNull(employerId: String, payRunId: String): Boolean {
        val updated = jdbcTemplate.update(
            """
            UPDATE pay_run
            SET finalize_completed_at = COALESCE(finalize_completed_at, CURRENT_TIMESTAMP),
                updated_at = CURRENT_TIMESTAMP
            WHERE employer_id = ? AND pay_run_id = ?
              AND finalize_completed_at IS NULL
            """.trimIndent(),
            employerId,
            payRunId,
        )
        return updated == 1
    }

    /**
     * Attach a correction linkage to a pay run if unset, or verify it matches if already set.
     */
    fun acceptCorrectionOfPayRunId(employerId: String, payRunId: String, correctionOfPayRunId: String): Boolean {
        // Set if NULL, or keep existing.
        jdbcTemplate.update(
            """
            UPDATE pay_run
            SET correction_of_pay_run_id = COALESCE(correction_of_pay_run_id, ?),
                updated_at = CURRENT_TIMESTAMP
            WHERE employer_id = ? AND pay_run_id = ?
            """.trimIndent(),
            correctionOfPayRunId,
            employerId,
            payRunId,
        )

        val stored = jdbcTemplate.query(
            """
            SELECT correction_of_pay_run_id
            FROM pay_run
            WHERE employer_id = ? AND pay_run_id = ?
            """.trimIndent(),
            { rs, _ -> rs.getString("correction_of_pay_run_id") },
            employerId,
            payRunId,
        ).firstOrNull()

        return stored == null || stored == correctionOfPayRunId
    }

    fun findCorrectionOfPayRunId(employerId: String, payRunId: String): String? = jdbcTemplate.query(
        """
            SELECT correction_of_pay_run_id
            FROM pay_run
            WHERE employer_id = ? AND pay_run_id = ?
        """.trimIndent(),
        { rs, _ -> rs.getString("correction_of_pay_run_id") },
        employerId,
        payRunId,
    ).firstOrNull()

    fun markApprovedIfPending(employerId: String, payRunId: String): Boolean {
        val updated = jdbcTemplate.update(
            """
            UPDATE pay_run
            SET approval_status = 'APPROVED',
                approved_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE employer_id = ? AND pay_run_id = ?
              AND approval_status = 'PENDING'
            """.trimIndent(),
            employerId,
            payRunId,
        )
        return updated == 1
    }

    fun setPaymentStatus(employerId: String, payRunId: String, paymentStatus: com.example.usbilling.orchestrator.payrun.model.PaymentStatus): Boolean {
        // Enforce monotonic-ish transitions to avoid accidental regressions, but allow
        // PARTIALLY_PAID updates from projections.
        val updated = jdbcTemplate.update(
            """
            UPDATE pay_run
            SET payment_status = ?,
                paid_at = CASE WHEN ? = 'PAID' THEN CURRENT_TIMESTAMP ELSE paid_at END,
                updated_at = CURRENT_TIMESTAMP
            WHERE employer_id = ? AND pay_run_id = ?
              AND (
                payment_status = ?
                OR (payment_status = 'UNPAID' AND ? IN ('PAYING','PAID','FAILED','PARTIALLY_PAID'))
                OR (payment_status = 'PAYING' AND ? IN ('PAID','FAILED','PARTIALLY_PAID'))
                OR (payment_status = 'PARTIALLY_PAID' AND ? IN ('PAID','FAILED','PARTIALLY_PAID'))
                OR (payment_status = 'FAILED' AND ? IN ('FAILED','PARTIALLY_PAID','PAID'))
                OR (payment_status = 'PAID' AND ? = 'PAID')
              )
            """.trimIndent(),
            paymentStatus.name,
            paymentStatus.name,
            employerId,
            payRunId,
            paymentStatus.name,
            paymentStatus.name,
            paymentStatus.name,
            paymentStatus.name,
            paymentStatus.name,
            paymentStatus.name,
        )
        return updated == 1
    }

    fun findPayRunIdByPaymentInitiateIdempotencyKey(employerId: String, idempotencyKey: String): String? = jdbcTemplate.query(
        """
            SELECT pay_run_id
            FROM pay_run
            WHERE employer_id = ? AND payment_initiate_idempotency_key = ?
            LIMIT 1
        """.trimIndent(),
        { rs, _ -> rs.getString("pay_run_id") },
        employerId,
        idempotencyKey,
    ).firstOrNull()

    fun findPaymentInitiateIdempotencyKeyForPayRun(employerId: String, payRunId: String): String? = jdbcTemplate.query(
        """
            SELECT payment_initiate_idempotency_key
            FROM pay_run
            WHERE employer_id = ? AND pay_run_id = ?
        """.trimIndent(),
        { rs, _ -> rs.getString("payment_initiate_idempotency_key") },
        employerId,
        payRunId,
    ).firstOrNull()

    /**
     * Attach an idempotency key to this payrun if it is currently unset, or verify it matches if already set.
     *
     * Returns true when the key is accepted (set or already matches), false otherwise.
     */
    fun acceptPaymentInitiateIdempotencyKey(employerId: String, payRunId: String, idempotencyKey: String): Boolean {
        val updated = jdbcTemplate.update(
            """
            UPDATE pay_run
            SET payment_initiate_idempotency_key = COALESCE(payment_initiate_idempotency_key, ?),
                updated_at = CURRENT_TIMESTAMP
            WHERE employer_id = ? AND pay_run_id = ?
              AND (payment_initiate_idempotency_key IS NULL OR payment_initiate_idempotency_key = ?)
            """.trimIndent(),
            idempotencyKey,
            employerId,
            payRunId,
            idempotencyKey,
        )
        return updated == 1
    }
}
