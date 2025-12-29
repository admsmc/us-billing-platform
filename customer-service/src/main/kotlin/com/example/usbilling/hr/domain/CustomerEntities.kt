package com.example.usbilling.hr.domain

import com.fasterxml.jackson.annotation.JsonIgnore
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Transient
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Customer entity - core customer master record.
 */
@Table("customer")
data class CustomerEntity(
    @Id
    @Column("customer_id")
    val customerId: String,

    @Column("utility_id")
    val utilityId: String,

    @Column("account_number")
    val accountNumber: String,

    @Column("customer_name")
    val customerName: String,

    @Column("service_address")
    val serviceAddress: String,

    @Column("customer_class")
    val customerClass: String?,

    @Column("active")
    val active: Boolean = true,

    @Column("created_at")
    val createdAt: Instant = Instant.now(),

    @Column("updated_at")
    val updatedAt: Instant = Instant.now(),
    
    @Transient
    @JsonIgnore
    val isNewEntity: Boolean = true
) : Persistable<String> {
    override fun getId(): String = customerId
    @JsonIgnore
    override fun isNew(): Boolean = isNewEntity
}

/**
 * Meter entity - meter installation record.
 */
@Table("meter")
data class MeterEntity(
    @Id
    @Column("meter_id")
    val meterId: String,

    @Column("customer_id")
    val customerId: String,

    @Column("utility_service_type")
    val utilityServiceType: String,

    @Column("meter_number")
    val meterNumber: String,

    @Column("install_date")
    val installDate: LocalDate,

    @Column("removal_date")
    val removalDate: LocalDate? = null,

    @Column("active")
    val active: Boolean = true,

    @Column("created_at")
    val createdAt: Instant = Instant.now(),
    
    @Transient
    @JsonIgnore
    val isNewEntity: Boolean = true
) : Persistable<String> {
    override fun getId(): String = meterId
    @JsonIgnore
    override fun isNew(): Boolean = isNewEntity
}

/**
 * Billing period entity - billing cycle window.
 */
@Table("billing_period")
data class BillingPeriodEntity(
    @Id
    @Column("period_id")
    val periodId: String,

    @Column("customer_id")
    val customerId: String,

    @Column("start_date")
    val startDate: LocalDate,

    @Column("end_date")
    val endDate: LocalDate,

    @Column("status")
    val status: String,

    @Column("created_at")
    val createdAt: Instant = Instant.now(),

    @Column("updated_at")
    val updatedAt: Instant = Instant.now(),
    
    @Transient
    @JsonIgnore
    val isNewEntity: Boolean = true
) : Persistable<String> {
    override fun getId(): String = periodId
    @JsonIgnore
    override fun isNew(): Boolean = isNewEntity
}

/**
 * Meter read entity - usage data point.
 */
@Table("meter_read")
data class MeterReadEntity(
    @Id
    @Column("read_id")
    val readId: String,

    @Column("meter_id")
    val meterId: String,

    @Column("billing_period_id")
    val billingPeriodId: String?,

    @Column("read_date")
    val readDate: LocalDate,

    @Column("reading_value")
    val readingValue: BigDecimal,

    @Column("reading_type")
    val readingType: String,

    @Column("recorded_at")
    val recordedAt: Instant = Instant.now(),
    
    @Transient
    @JsonIgnore
    val isNewEntity: Boolean = true
) : Persistable<String> {
    override fun getId(): String = readId
    @JsonIgnore
    override fun isNew(): Boolean = isNewEntity
}
