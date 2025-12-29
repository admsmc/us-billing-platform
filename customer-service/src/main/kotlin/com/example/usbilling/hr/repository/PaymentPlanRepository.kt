package com.example.usbilling.hr.repository

import com.example.usbilling.hr.domain.*
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
class PaymentPlanRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) {

    /**
     * Save a payment plan using bitemporal SCD2 pattern.
     * Creates a new row with system_from = now, system_to = 9999-12-31.
     * If updating, supersedes the current version by setting its system_to = now.
     */
    fun savePlan(plan: PaymentPlan): PaymentPlan {
        // First, supersede the current version if it exists
        val currentVersion = findCurrentPlanVersion(plan.planId)
        if (currentVersion != null) {
            supersedePlanCurrent(plan.planId)
        }

        // Insert new version
        val sql = """
            INSERT INTO payment_plan (
                plan_id, utility_id, customer_id, account_id, 
                plan_type, status, total_amount_cents, down_payment_cents,
                remaining_balance_cents, installment_amount_cents, installment_count, installments_paid,
                payment_frequency, start_date, first_payment_date, final_payment_date,
                missed_payments, max_missed_payments,
                system_from, system_to, modified_by,
                created_at, created_by, cancelled_at, cancelled_reason
            ) VALUES (
                :planId, :utilityId, :customerId, :accountId,
                :planType, :status, :totalAmountCents, :downPaymentCents,
                :remainingBalanceCents, :installmentAmountCents, :installmentCount, :installmentsPaid,
                :paymentFrequency, :startDate, :firstPaymentDate, :finalPaymentDate,
                :missedPayments, :maxMissedPayments,
                :systemFrom, :systemTo, :modifiedBy,
                :createdAt, :createdBy, :cancelledAt, :cancelledReason
            )
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("planId", plan.planId)
            .addValue("utilityId", plan.utilityId)
            .addValue("customerId", plan.customerId)
            .addValue("accountId", plan.accountId)
            .addValue("planType", plan.planType.name)
            .addValue("status", plan.status.name)
            .addValue("totalAmountCents", plan.totalAmountCents)
            .addValue("downPaymentCents", plan.downPaymentCents)
            .addValue("remainingBalanceCents", plan.remainingBalanceCents)
            .addValue("installmentAmountCents", plan.installmentAmountCents)
            .addValue("installmentCount", plan.installmentCount)
            .addValue("installmentsPaid", plan.installmentsPaid)
            .addValue("paymentFrequency", plan.paymentFrequency.name)
            .addValue("startDate", plan.startDate)
            .addValue("firstPaymentDate", plan.firstPaymentDate)
            .addValue("finalPaymentDate", plan.finalPaymentDate)
            .addValue("missedPayments", plan.missedPayments)
            .addValue("maxMissedPayments", plan.maxMissedPayments)
            .addValue("systemFrom", plan.systemFrom)
            .addValue("systemTo", plan.systemTo)
            .addValue("modifiedBy", plan.modifiedBy)
            .addValue("createdAt", plan.createdAt)
            .addValue("createdBy", plan.createdBy)
            .addValue("cancelledAt", plan.cancelledAt)
            .addValue("cancelledReason", plan.cancelledReason)

        jdbcTemplate.update(sql, params)
        return plan
    }

    /**
     * Save a payment plan installment using bitemporal SCD2 pattern.
     */
    fun saveInstallment(installment: PaymentPlanInstallment): PaymentPlanInstallment {
        // First, supersede the current version if it exists
        val currentVersion = findCurrentInstallmentVersion(installment.installmentId)
        if (currentVersion != null) {
            supersedeInstallmentCurrent(installment.installmentId)
        }

        // Insert new version
        val sql = """
            INSERT INTO payment_plan_installment (
                installment_id, plan_id, installment_number, due_date,
                amount_cents, paid_amount_cents, status, paid_at,
                system_from, system_to, modified_by
            ) VALUES (
                :installmentId, :planId, :installmentNumber, :dueDate,
                :amountCents, :paidAmountCents, :status, :paidAt,
                :systemFrom, :systemTo, :modifiedBy
            )
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("installmentId", installment.installmentId)
            .addValue("planId", installment.planId)
            .addValue("installmentNumber", installment.installmentNumber)
            .addValue("dueDate", installment.dueDate)
            .addValue("amountCents", installment.amountCents)
            .addValue("paidAmountCents", installment.paidAmountCents)
            .addValue("status", installment.status.name)
            .addValue("paidAt", installment.paidAt)
            .addValue("systemFrom", installment.systemFrom)
            .addValue("systemTo", installment.systemTo)
            .addValue("modifiedBy", installment.modifiedBy)

        jdbcTemplate.update(sql, params)
        return installment
    }

    /**
     * Save a payment plan payment (append-only, no SCD2).
     */
    fun savePayment(payment: PaymentPlanPayment): PaymentPlanPayment {
        val sql = """
            INSERT INTO payment_plan_payment (
                id, plan_id, installment_id, payment_id, amount_cents, applied_at
            ) VALUES (
                :id, :planId, :installmentId, :paymentId, :amountCents, :appliedAt
            )
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("id", payment.id)
            .addValue("planId", payment.planId)
            .addValue("installmentId", payment.installmentId)
            .addValue("paymentId", payment.paymentId)
            .addValue("amountCents", payment.amountCents)
            .addValue("appliedAt", payment.appliedAt)

        jdbcTemplate.update(sql, params)
        return payment
    }

    /**
     * Supersede the current plan version by setting system_to = CURRENT_TIMESTAMP.
     * This is the only UPDATE operation - it closes the temporal window.
     */
    private fun supersedePlanCurrent(planId: String) {
        val sql = """
            UPDATE payment_plan 
            SET system_to = CURRENT_TIMESTAMP 
            WHERE plan_id = :planId 
              AND system_to = TIMESTAMP '9999-12-31 23:59:59'
        """.trimIndent()

        jdbcTemplate.update(sql, MapSqlParameterSource("planId", planId))
    }

    /**
     * Supersede the current installment version by setting system_to = CURRENT_TIMESTAMP.
     */
    private fun supersedeInstallmentCurrent(installmentId: String) {
        val sql = """
            UPDATE payment_plan_installment 
            SET system_to = CURRENT_TIMESTAMP 
            WHERE installment_id = :installmentId 
              AND system_to = TIMESTAMP '9999-12-31 23:59:59'
        """.trimIndent()

        jdbcTemplate.update(sql, MapSqlParameterSource("installmentId", installmentId))
    }

    /**
     * Find the current version of a plan (system_to = 9999-12-31).
     */
    private fun findCurrentPlanVersion(planId: String): PaymentPlan? {
        val sql = """
            SELECT * FROM payment_plan 
            WHERE plan_id = :planId 
              AND system_to = TIMESTAMP '9999-12-31 23:59:59'
        """.trimIndent()

        val params = MapSqlParameterSource("planId", planId)
        return jdbcTemplate.query(sql, params) { rs, _ -> mapPaymentPlan(rs) }
            .firstOrNull()
    }

    /**
     * Find the current version of an installment (system_to = 9999-12-31).
     */
    private fun findCurrentInstallmentVersion(installmentId: String): PaymentPlanInstallment? {
        val sql = """
            SELECT * FROM payment_plan_installment 
            WHERE installment_id = :installmentId 
              AND system_to = TIMESTAMP '9999-12-31 23:59:59'
        """.trimIndent()

        val params = MapSqlParameterSource("installmentId", installmentId)
        return jdbcTemplate.query(sql, params) { rs, _ -> mapPaymentPlanInstallment(rs) }
            .firstOrNull()
    }

    /**
     * Find current version of a plan by ID.
     */
    fun findById(planId: String): PaymentPlan? {
        val sql = """
            SELECT * FROM payment_plan 
            WHERE plan_id = :planId 
              AND system_to = TIMESTAMP '9999-12-31 23:59:59'
        """.trimIndent()

        val params = MapSqlParameterSource("planId", planId)
        return jdbcTemplate.query(sql, params) { rs, _ -> mapPaymentPlan(rs) }
            .firstOrNull()
    }

    /**
     * Find current versions of plans by customer.
     */
    fun findByCustomerId(customerId: String, status: PaymentPlanStatus? = null): List<PaymentPlan> {
        val sql = buildString {
            append("SELECT * FROM payment_plan WHERE customer_id = :customerId")
            append(" AND system_to = TIMESTAMP '9999-12-31 23:59:59'")
            if (status != null) append(" AND status = :status")
            append(" ORDER BY created_at DESC")
        }

        val params = MapSqlParameterSource()
            .addValue("customerId", customerId)
            .addValue("status", status?.name)

        return jdbcTemplate.query(sql, params) { rs, _ -> mapPaymentPlan(rs) }
    }

    /**
     * Find current versions of installments by plan.
     */
    fun findInstallmentsByPlanId(planId: String): List<PaymentPlanInstallment> {
        val sql = """
            SELECT * FROM payment_plan_installment 
            WHERE plan_id = :planId 
              AND system_to = TIMESTAMP '9999-12-31 23:59:59'
            ORDER BY installment_number ASC
        """.trimIndent()

        val params = MapSqlParameterSource("planId", planId)
        return jdbcTemplate.query(sql, params) { rs, _ -> mapPaymentPlanInstallment(rs) }
    }

    /**
     * Find payments by plan (append-only, no temporal filtering needed).
     */
    fun findPaymentsByPlanId(planId: String): List<PaymentPlanPayment> {
        val sql = """
            SELECT * FROM payment_plan_payment 
            WHERE plan_id = :planId 
            ORDER BY applied_at ASC
        """.trimIndent()

        val params = MapSqlParameterSource("planId", planId)
        return jdbcTemplate.query(sql, params) { rs, _ -> mapPaymentPlanPayment(rs) }
    }

    private fun mapPaymentPlan(rs: ResultSet) = PaymentPlan(
        planId = rs.getString("plan_id"),
        utilityId = rs.getString("utility_id"),
        customerId = rs.getString("customer_id"),
        accountId = rs.getString("account_id"),
        planType = PaymentPlanType.valueOf(rs.getString("plan_type")),
        status = PaymentPlanStatus.valueOf(rs.getString("status")),
        totalAmountCents = rs.getLong("total_amount_cents"),
        downPaymentCents = rs.getLong("down_payment_cents"),
        remainingBalanceCents = rs.getLong("remaining_balance_cents"),
        installmentAmountCents = rs.getLong("installment_amount_cents"),
        installmentCount = rs.getInt("installment_count"),
        installmentsPaid = rs.getInt("installments_paid"),
        paymentFrequency = PaymentFrequency.valueOf(rs.getString("payment_frequency")),
        startDate = rs.getDate("start_date").toLocalDate(),
        firstPaymentDate = rs.getDate("first_payment_date").toLocalDate(),
        finalPaymentDate = rs.getDate("final_payment_date").toLocalDate(),
        missedPayments = rs.getInt("missed_payments"),
        maxMissedPayments = rs.getInt("max_missed_payments"),
        systemFrom = rs.getTimestamp("system_from").toLocalDateTime(),
        systemTo = rs.getTimestamp("system_to").toLocalDateTime(),
        modifiedBy = rs.getString("modified_by"),
        createdAt = rs.getTimestamp("created_at").toLocalDateTime(),
        createdBy = rs.getString("created_by"),
        cancelledAt = rs.getTimestamp("cancelled_at")?.toLocalDateTime(),
        cancelledReason = rs.getString("cancelled_reason"),
    )

    private fun mapPaymentPlanInstallment(rs: ResultSet) = PaymentPlanInstallment(
        installmentId = rs.getString("installment_id"),
        planId = rs.getString("plan_id"),
        installmentNumber = rs.getInt("installment_number"),
        dueDate = rs.getDate("due_date").toLocalDate(),
        amountCents = rs.getLong("amount_cents"),
        paidAmountCents = rs.getLong("paid_amount_cents"),
        status = InstallmentStatus.valueOf(rs.getString("status")),
        paidAt = rs.getTimestamp("paid_at")?.toLocalDateTime(),
        systemFrom = rs.getTimestamp("system_from").toLocalDateTime(),
        systemTo = rs.getTimestamp("system_to").toLocalDateTime(),
        modifiedBy = rs.getString("modified_by"),
    )

    private fun mapPaymentPlanPayment(rs: ResultSet) = PaymentPlanPayment(
        id = rs.getString("id"),
        planId = rs.getString("plan_id"),
        installmentId = rs.getString("installment_id"),
        paymentId = rs.getString("payment_id"),
        amountCents = rs.getLong("amount_cents"),
        appliedAt = rs.getTimestamp("applied_at").toLocalDateTime(),
    )
}
