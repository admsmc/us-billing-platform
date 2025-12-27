package com.example.usbilling.orchestrator.payments.persistence

import com.example.usbilling.orchestrator.payrun.model.PaymentStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class PaymentStatusProjectionRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun updatePaycheckPaymentStatus(employerId: String, paycheckId: String, paymentStatus: PaymentStatus): Int = jdbcTemplate.update(
        """
            UPDATE paycheck
            SET payment_status = ?,
                paid_at = CASE WHEN ? = 'PAID' THEN CURRENT_TIMESTAMP ELSE paid_at END,
                updated_at = CURRENT_TIMESTAMP
            WHERE employer_id = ? AND paycheck_id = ?
        """.trimIndent(),
        paymentStatus.name,
        paymentStatus.name,
        employerId,
        paycheckId,
    )

    data class PayRunPaymentCounts(
        val total: Int,
        val paid: Int,
        val failed: Int,
    )

    fun getPayRunPaymentCounts(employerId: String, payRunId: String): PayRunPaymentCounts = jdbcTemplate.query(
        """
            SELECT
              COUNT(*) AS total,
              SUM(CASE WHEN payment_status = 'PAID' THEN 1 ELSE 0 END) AS paid,
              SUM(CASE WHEN payment_status = 'FAILED' THEN 1 ELSE 0 END) AS failed
            FROM paycheck
            WHERE employer_id = ? AND pay_run_id = ?
        """.trimIndent(),
        { rs, _ ->
            PayRunPaymentCounts(
                total = rs.getInt("total"),
                paid = rs.getInt("paid"),
                failed = rs.getInt("failed"),
            )
        },
        employerId,
        payRunId,
    ).firstOrNull() ?: PayRunPaymentCounts(total = 0, paid = 0, failed = 0)
}
