package com.example.uspayroll.orchestrator.persistence

import com.example.uspayroll.orchestrator.payrun.model.ApprovalStatus
import com.example.uspayroll.orchestrator.payrun.model.PaymentStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class PaycheckLifecycleRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun setApprovalStatusForPayRun(
        employerId: String,
        payRunId: String,
        approvalStatus: ApprovalStatus,
    ): Int {
        return jdbcTemplate.update(
            """
            UPDATE paycheck
            SET approval_status = ?,
                approved_at = CASE WHEN ? = 'APPROVED' THEN CURRENT_TIMESTAMP ELSE approved_at END,
                updated_at = CURRENT_TIMESTAMP
            WHERE employer_id = ? AND pay_run_id = ?
            """.trimIndent(),
            approvalStatus.name,
            approvalStatus.name,
            employerId,
            payRunId,
        )
    }

    fun setPaymentStatusForPayRun(
        employerId: String,
        payRunId: String,
        paymentStatus: PaymentStatus,
    ): Int {
        return jdbcTemplate.update(
            """
            UPDATE paycheck
            SET payment_status = ?,
                paid_at = CASE WHEN ? = 'PAID' THEN CURRENT_TIMESTAMP ELSE paid_at END,
                updated_at = CURRENT_TIMESTAMP
            WHERE employer_id = ? AND pay_run_id = ?
            """.trimIndent(),
            paymentStatus.name,
            paymentStatus.name,
            employerId,
            payRunId,
        )
    }
}
