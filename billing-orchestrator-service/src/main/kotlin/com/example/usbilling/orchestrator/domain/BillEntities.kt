package com.example.usbilling.orchestrator.domain

import org.springframework.data.annotation.Id
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

@Table("bill")
data class BillEntity(
    @Id @Column("bill_id") val billId: String,
    @Column("customer_id") val customerId: String,
    @Column("utility_id") val utilityId: String,
    @Column("billing_period_id") val billingPeriodId: String,
    @Column("bill_number") val billNumber: String?,
    @Column("status") val status: String,
    @Column("total_amount_cents") val totalAmountCents: Long,
    @Column("due_date") val dueDate: LocalDate,
    @Column("bill_date") val billDate: LocalDate,
    @Column("created_at") val createdAt: Instant = Instant.now(),
    @Column("updated_at") val updatedAt: Instant = Instant.now(),
)

@Table("bill_line")
data class BillLineEntity(
    @Id @Column("line_id") val lineId: String,
    @Column("bill_id") val billId: String,
    @Column("service_type") val serviceType: String,
    @Column("charge_type") val chargeType: String,
    @Column("description") val description: String?,
    @Column("usage_amount") val usageAmount: BigDecimal?,
    @Column("rate_value_cents") val rateValueCents: Long?,
    @Column("line_amount_cents") val lineAmountCents: Long,
    @Column("line_order") val lineOrder: Int,
)

@Table("bill_event")
data class BillEventEntity(
    @Id @Column("event_id") val eventId: String,
    @Column("bill_id") val billId: String,
    @Column("event_type") val eventType: String,
    @Column("event_data") val eventData: String?,
    @Column("created_at") val createdAt: Instant = Instant.now(),
)

@Repository
interface BillRepository : CrudRepository<BillEntity, String> {
    @Query("SELECT * FROM bill WHERE customer_id = :customerId ORDER BY bill_date DESC")
    fun findByCustomerId(@Param("customerId") customerId: String): List<BillEntity>

    @Query("SELECT * FROM bill WHERE utility_id = :utilityId AND status = :status")
    fun findByUtilityIdAndStatus(
        @Param("utilityId") utilityId: String,
        @Param("status") status: String,
    ): List<BillEntity>
}

@Repository
interface BillLineRepository : CrudRepository<BillLineEntity, String> {
    @Query("SELECT * FROM bill_line WHERE bill_id = :billId ORDER BY line_order ASC")
    fun findByBillId(@Param("billId") billId: String): List<BillLineEntity>
}

@Repository
interface BillEventRepository : CrudRepository<BillEventEntity, String> {
    @Query("SELECT * FROM bill_event WHERE bill_id = :billId ORDER BY created_at DESC")
    fun findByBillId(@Param("billId") billId: String): List<BillEventEntity>
}
