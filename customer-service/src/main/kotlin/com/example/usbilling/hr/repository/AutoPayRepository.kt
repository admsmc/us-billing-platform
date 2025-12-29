package com.example.usbilling.hr.repository

import com.example.usbilling.hr.domain.*
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.LocalDate

@Repository
class AutoPayEnrollmentRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) {

    fun save(enrollment: AutoPayEnrollment): AutoPayEnrollment {
        val sql = """
            INSERT INTO autopay_enrollment (
                enrollment_id, utility_id, customer_id, account_id, payment_method_id,
                status, payment_timing, fixed_day_of_month, amount_type, fixed_amount_cents,
                enrolled_at, enrolled_by, cancelled_at, cancelled_reason, consecutive_failures
            ) VALUES (
                :enrollmentId, :utilityId, :customerId, :accountId, :paymentMethodId,
                :status, :paymentTiming, :fixedDayOfMonth, :amountType, :fixedAmountCents,
                :enrolledAt, :enrolledBy, :cancelledAt, :cancelledReason, :consecutiveFailures
            )
            ON CONFLICT (enrollment_id) DO UPDATE SET
                status = EXCLUDED.status,
                payment_timing = EXCLUDED.payment_timing,
                fixed_day_of_month = EXCLUDED.fixed_day_of_month,
                amount_type = EXCLUDED.amount_type,
                fixed_amount_cents = EXCLUDED.fixed_amount_cents,
                cancelled_at = EXCLUDED.cancelled_at,
                cancelled_reason = EXCLUDED.cancelled_reason,
                consecutive_failures = EXCLUDED.consecutive_failures
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("enrollmentId", enrollment.enrollmentId)
            .addValue("utilityId", enrollment.utilityId)
            .addValue("customerId", enrollment.customerId)
            .addValue("accountId", enrollment.accountId)
            .addValue("paymentMethodId", enrollment.paymentMethodId)
            .addValue("status", enrollment.status.name)
            .addValue("paymentTiming", enrollment.paymentTiming.name)
            .addValue("fixedDayOfMonth", enrollment.fixedDayOfMonth)
            .addValue("amountType", enrollment.amountType.name)
            .addValue("fixedAmountCents", enrollment.fixedAmountCents)
            .addValue("enrolledAt", enrollment.enrolledAt)
            .addValue("enrolledBy", enrollment.enrolledBy)
            .addValue("cancelledAt", enrollment.cancelledAt)
            .addValue("cancelledReason", enrollment.cancelledReason)
            .addValue("consecutiveFailures", enrollment.consecutiveFailures)

        jdbcTemplate.update(sql, params)
        return enrollment
    }

    fun findById(enrollmentId: String): AutoPayEnrollment? {
        val sql = """
            SELECT * FROM autopay_enrollment WHERE enrollment_id = :enrollmentId
        """.trimIndent()

        val params = MapSqlParameterSource("enrollmentId", enrollmentId)
        return jdbcTemplate.query(sql, params) { rs, _ -> mapEnrollment(rs) }
            .firstOrNull()
    }

    fun findByAccountId(accountId: String): AutoPayEnrollment? {
        val sql = """
            SELECT * FROM autopay_enrollment 
            WHERE account_id = :accountId 
              AND status != 'CANCELLED'
            ORDER BY enrolled_at DESC 
            LIMIT 1
        """.trimIndent()

        val params = MapSqlParameterSource("accountId", accountId)
        return jdbcTemplate.query(sql, params) { rs, _ -> mapEnrollment(rs) }
            .firstOrNull()
    }

    fun findByCustomerId(customerId: String): List<AutoPayEnrollment> {
        val sql = """
            SELECT * FROM autopay_enrollment 
            WHERE customer_id = :customerId 
            ORDER BY enrolled_at DESC
        """.trimIndent()

        val params = MapSqlParameterSource("customerId", customerId)
        return jdbcTemplate.query(sql, params) { rs, _ -> mapEnrollment(rs) }
    }

    fun findActiveEnrollmentsDueOn(date: LocalDate): List<AutoPayEnrollment> {
        val sql = """
            SELECT * FROM autopay_enrollment 
            WHERE status = 'ACTIVE'
        """.trimIndent()

        return jdbcTemplate.query(sql, MapSqlParameterSource()) { rs, _ -> mapEnrollment(rs) }
    }

    private fun mapEnrollment(rs: ResultSet) = AutoPayEnrollment(
        enrollmentId = rs.getString("enrollment_id"),
        utilityId = rs.getString("utility_id"),
        customerId = rs.getString("customer_id"),
        accountId = rs.getString("account_id"),
        paymentMethodId = rs.getString("payment_method_id"),
        status = AutoPayStatus.valueOf(rs.getString("status")),
        paymentTiming = PaymentTiming.valueOf(rs.getString("payment_timing")),
        fixedDayOfMonth = rs.getObject("fixed_day_of_month") as? Int,
        amountType = AutoPayAmountType.valueOf(rs.getString("amount_type")),
        fixedAmountCents = rs.getObject("fixed_amount_cents") as? Long,
        enrolledAt = rs.getTimestamp("enrolled_at").toLocalDateTime(),
        enrolledBy = rs.getString("enrolled_by"),
        cancelledAt = rs.getTimestamp("cancelled_at")?.toLocalDateTime(),
        cancelledReason = rs.getString("cancelled_reason"),
        consecutiveFailures = rs.getInt("consecutive_failures"),
    )
}

@Repository
class AutoPayExecutionRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) {

    fun save(execution: AutoPayExecution): AutoPayExecution {
        val sql = """
            INSERT INTO autopay_execution (
                execution_id, enrollment_id, bill_id, scheduled_date, executed_at,
                amount_cents, status, failure_reason, payment_id, retry_count, created_at
            ) VALUES (
                :executionId, :enrollmentId, :billId, :scheduledDate, :executedAt,
                :amountCents, :status, :failureReason, :paymentId, :retryCount, :createdAt
            )
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("executionId", execution.executionId)
            .addValue("enrollmentId", execution.enrollmentId)
            .addValue("billId", execution.billId)
            .addValue("scheduledDate", execution.scheduledDate)
            .addValue("executedAt", execution.executedAt)
            .addValue("amountCents", execution.amountCents)
            .addValue("status", execution.status.name)
            .addValue("failureReason", execution.failureReason)
            .addValue("paymentId", execution.paymentId)
            .addValue("retryCount", execution.retryCount)
            .addValue("createdAt", execution.createdAt)

        jdbcTemplate.update(sql, params)
        return execution
    }

    fun findById(executionId: String): AutoPayExecution? {
        val sql = """
            SELECT * FROM autopay_execution WHERE execution_id = :executionId
        """.trimIndent()

        val params = MapSqlParameterSource("executionId", executionId)
        return jdbcTemplate.query(sql, params) { rs, _ -> mapExecution(rs) }
            .firstOrNull()
    }

    fun findByEnrollmentId(enrollmentId: String, limit: Int = 50): List<AutoPayExecution> {
        val sql = """
            SELECT * FROM autopay_execution 
            WHERE enrollment_id = :enrollmentId 
            ORDER BY scheduled_date DESC 
            LIMIT :limit
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("enrollmentId", enrollmentId)
            .addValue("limit", limit)

        return jdbcTemplate.query(sql, params) { rs, _ -> mapExecution(rs) }
    }

    fun findScheduledForDate(date: LocalDate): List<AutoPayExecution> {
        val sql = """
            SELECT * FROM autopay_execution 
            WHERE scheduled_date = :date 
              AND status = 'SCHEDULED'
        """.trimIndent()

        val params = MapSqlParameterSource("date", date)
        return jdbcTemplate.query(sql, params) { rs, _ -> mapExecution(rs) }
    }

    fun findFailedForRetry(maxRetries: Int = 3): List<AutoPayExecution> {
        val sql = """
            SELECT * FROM autopay_execution 
            WHERE status = 'FAILED' 
              AND retry_count < :maxRetries 
              AND scheduled_date >= CURRENT_DATE - INTERVAL '7 days'
            ORDER BY scheduled_date ASC
        """.trimIndent()

        val params = MapSqlParameterSource("maxRetries", maxRetries)
        return jdbcTemplate.query(sql, params) { rs, _ -> mapExecution(rs) }
    }

    fun updateStatus(
        executionId: String,
        status: ExecutionStatus,
        executedAt: java.time.LocalDateTime?,
        paymentId: String?,
        failureReason: String?,
        retryCount: Int,
    ) {
        val sql = """
            UPDATE autopay_execution 
            SET status = :status,
                executed_at = :executedAt,
                payment_id = :paymentId,
                failure_reason = :failureReason,
                retry_count = :retryCount
            WHERE execution_id = :executionId
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("executionId", executionId)
            .addValue("status", status.name)
            .addValue("executedAt", executedAt)
            .addValue("paymentId", paymentId)
            .addValue("failureReason", failureReason)
            .addValue("retryCount", retryCount)

        jdbcTemplate.update(sql, params)
    }

    private fun mapExecution(rs: ResultSet) = AutoPayExecution(
        executionId = rs.getString("execution_id"),
        enrollmentId = rs.getString("enrollment_id"),
        billId = rs.getString("bill_id"),
        scheduledDate = rs.getDate("scheduled_date").toLocalDate(),
        executedAt = rs.getTimestamp("executed_at")?.toLocalDateTime(),
        amountCents = rs.getLong("amount_cents"),
        status = ExecutionStatus.valueOf(rs.getString("status")),
        failureReason = rs.getString("failure_reason"),
        paymentId = rs.getString("payment_id"),
        retryCount = rs.getInt("retry_count"),
        createdAt = rs.getTimestamp("created_at").toLocalDateTime(),
    )
}
