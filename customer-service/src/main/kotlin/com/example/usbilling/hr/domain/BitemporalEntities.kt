package com.example.usbilling.hr.domain

import com.fasterxml.jackson.annotation.JsonIgnore
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Transient
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.time.LocalDate

/**
 * Bitemporal customer account entity using SCD2 pattern.
 *
 * Tracks both effective time (when data was true in real world) and
 * system time (when system knew about the data).
 *
 * Primary key: (account_id, effective_from, system_from)
 *
 * Query patterns:
 * - Current version: system_from <= now AND system_to > now AND effective_from <= asOf AND effective_to > asOf
 * - Historical: Use specific system_from and effective_from values
 * - All versions: Query all rows for account_id
 */
@Table("customer_account_effective")
data class CustomerAccountEffective(
    @Id
    @Column("account_id")
    val accountId: String,

    @Column("utility_id")
    val utilityId: String,

    @Column("customer_id")
    val customerId: String,

    @Column("account_number")
    val accountNumber: String,

    @Column("account_type")
    val accountType: String,

    @Column("account_status")
    val accountStatus: String,

    @Column("holder_name")
    val holderName: String,

    @Column("holder_type")
    val holderType: String,

    @Column("identity_verified")
    val identityVerified: Boolean = false,

    @Column("tax_id")
    val taxId: String? = null,

    @Column("business_name")
    val businessName: String? = null,

    @Column("billing_address_line1")
    val billingAddressLine1: String,

    @Column("billing_address_line2")
    val billingAddressLine2: String? = null,

    @Column("billing_city")
    val billingCity: String,

    @Column("billing_state")
    val billingState: String,

    @Column("billing_postal_code")
    val billingPostalCode: String,

    @Column("billing_country")
    val billingCountry: String = "US",

    // Bitemporal columns
    @Column("effective_from")
    val effectiveFrom: LocalDate,

    @Column("effective_to")
    val effectiveTo: LocalDate,

    @Column("system_from")
    val systemFrom: Instant,

    @Column("system_to")
    val systemTo: Instant,

    // Audit fields
    @Column("created_by")
    val createdBy: String,

    @Column("modified_by")
    val modifiedBy: String,

    @Column("version_sequence")
    val versionSequence: Int = 1,

    @Column("change_reason")
    val changeReason: String? = null,
) : Persistable<String> {

    @Transient
    @get:JsonIgnore
    var isNewEntity: Boolean = true

    override fun getId(): String = accountId

    override fun isNew(): Boolean = isNewEntity

    companion object {
        /**
         * Create a new customer account version with all required fields.
         */
        fun create(
            accountId: String,
            utilityId: String,
            accountNumber: String,
            holderName: String,
            billingAddress: String, // Pipe-delimited: line1|line2|city|state|postal
            accountType: String = "RESIDENTIAL",
            effectiveFrom: LocalDate = LocalDate.now(),
            createdBy: String = "system",
        ): CustomerAccountEffective {
            val addressParts = billingAddress.split("|").map { it.trim() }
            val customerId = java.util.UUID.randomUUID().toString()

            return CustomerAccountEffective(
                accountId = accountId,
                utilityId = utilityId,
                customerId = customerId,
                accountNumber = accountNumber,
                accountType = accountType,
                accountStatus = "ACTIVE",
                holderName = holderName,
                holderType = "INDIVIDUAL",
                identityVerified = false,
                taxId = null,
                businessName = null,
                billingAddressLine1 = addressParts.getOrElse(0) { "" },
                billingAddressLine2 = addressParts.getOrNull(1)?.takeIf { it.isNotEmpty() },
                billingCity = addressParts.getOrElse(2) { "" },
                billingState = addressParts.getOrElse(3) { "" },
                billingPostalCode = addressParts.getOrElse(4) { "" },
                billingCountry = "US",
                effectiveFrom = effectiveFrom,
                effectiveTo = LocalDate.of(9999, 12, 31),
                systemFrom = Instant.now(),
                systemTo = Instant.parse("9999-12-31T23:59:59Z"),
                createdBy = createdBy,
                modifiedBy = createdBy,
                versionSequence = 1,
                changeReason = null,
            )
        }
    }
}

/**
 * Bitemporal service point entity using SCD2 pattern.
 *
 * Represents a physical location where utility service is provided.
 * Multiple meters can be associated with a service point.
 */
@Table("service_point_effective")
data class ServicePointEffective(
    @Id
    @Column("service_point_id")
    val servicePointId: String,

    @Column("utility_id")
    val utilityId: String,

    @Column("account_id")
    val accountId: String,

    @Column("address_id")
    val addressId: String,

    @Column("service_type")
    val serviceType: String,

    @Column("connection_status")
    val connectionStatus: String,

    @Column("rate_class")
    val rateClass: String? = null,

    // Bitemporal columns
    @Column("effective_from")
    val effectiveFrom: LocalDate,

    @Column("effective_to")
    val effectiveTo: LocalDate,

    @Column("system_from")
    val systemFrom: Instant,

    @Column("system_to")
    val systemTo: Instant,

    // Audit fields
    @Column("created_by")
    val createdBy: String,

    @Column("modified_by")
    val modifiedBy: String,

    @Column("version_sequence")
    val versionSequence: Int = 1,
) : Persistable<String> {

    @Transient
    @get:JsonIgnore
    var isNewEntity: Boolean = true

    override fun getId(): String = servicePointId
    override fun isNew(): Boolean = isNewEntity

    companion object {
        fun create(
            servicePointId: String,
            utilityId: String,
            accountId: String,
            addressId: String,
            serviceType: String,
            effectiveFrom: LocalDate = LocalDate.now(),
            createdBy: String = "system",
        ): ServicePointEffective = ServicePointEffective(
            servicePointId = servicePointId,
            utilityId = utilityId,
            accountId = accountId,
            addressId = addressId,
            serviceType = serviceType,
            connectionStatus = "CONNECTED",
            rateClass = null,
            effectiveFrom = effectiveFrom,
            effectiveTo = LocalDate.of(9999, 12, 31),
            systemFrom = Instant.now(),
            systemTo = Instant.parse("9999-12-31T23:59:59Z"),
            createdBy = createdBy,
            modifiedBy = createdBy,
            versionSequence = 1,
        )
    }
}

/**
 * Bitemporal meter entity using SCD2 pattern.
 *
 * Represents a utility meter installed at a service point.
 */
@Table("meter_effective")
data class MeterEffective(
    @Id
    @Column("meter_id")
    val meterId: String,

    @Column("service_point_id")
    val servicePointId: String,

    @Column("meter_serial")
    val meterSerial: String,

    @Column("meter_type")
    val meterType: String,

    @Column("manufacturer")
    val manufacturer: String? = null,

    @Column("model")
    val model: String? = null,

    @Column("install_date")
    val installDate: LocalDate? = null,

    @Column("last_read_date")
    val lastReadDate: LocalDate? = null,

    @Column("meter_status")
    val meterStatus: String,

    // Bitemporal columns
    @Column("effective_from")
    val effectiveFrom: LocalDate,

    @Column("effective_to")
    val effectiveTo: LocalDate,

    @Column("system_from")
    val systemFrom: Instant,

    @Column("system_to")
    val systemTo: Instant,

    // Audit fields
    @Column("created_by")
    val createdBy: String,

    @Column("modified_by")
    val modifiedBy: String,

    @Column("version_sequence")
    val versionSequence: Int = 1,
) : Persistable<String> {

    @Transient
    @get:JsonIgnore
    var isNewEntity: Boolean = true

    override fun getId(): String = meterId
    override fun isNew(): Boolean = isNewEntity

    companion object {
        fun create(
            meterId: String,
            servicePointId: String,
            meterSerial: String,
            meterType: String = "ELECTRIC_SMART",
            effectiveFrom: LocalDate = LocalDate.now(),
            createdBy: String = "system",
        ): MeterEffective = MeterEffective(
            meterId = meterId,
            servicePointId = servicePointId,
            meterSerial = meterSerial,
            meterType = meterType,
            manufacturer = null,
            model = null,
            installDate = effectiveFrom,
            lastReadDate = null,
            meterStatus = "ACTIVE",
            effectiveFrom = effectiveFrom,
            effectiveTo = LocalDate.of(9999, 12, 31),
            systemFrom = Instant.now(),
            systemTo = Instant.parse("9999-12-31T23:59:59Z"),
            createdBy = createdBy,
            modifiedBy = createdBy,
            versionSequence = 1,
        )
    }
}

/**
 * Bitemporal billing period entity using SCD2 pattern.
 */
@Table("billing_period_effective")
data class BillingPeriodEffective(
    @Id
    @Column("period_id")
    val periodId: String,

    @Column("account_id")
    val accountId: String,

    @Column("start_date")
    val startDate: LocalDate,

    @Column("end_date")
    val endDate: LocalDate,

    @Column("due_date")
    val dueDate: LocalDate,

    @Column("status")
    val status: String,

    // Bitemporal columns
    @Column("effective_from")
    val effectiveFrom: LocalDate,

    @Column("effective_to")
    val effectiveTo: LocalDate,

    @Column("system_from")
    val systemFrom: Instant,

    @Column("system_to")
    val systemTo: Instant,

    // Audit fields
    @Column("created_by")
    val createdBy: String,

    @Column("modified_by")
    val modifiedBy: String,

    @Column("version_sequence")
    val versionSequence: Int = 1,
) : Persistable<String> {

    @Transient
    @get:JsonIgnore
    var isNewEntity: Boolean = true

    override fun getId(): String = periodId
    override fun isNew(): Boolean = isNewEntity

    companion object {
        fun create(
            periodId: String,
            accountId: String,
            startDate: LocalDate,
            endDate: LocalDate,
            dueDate: LocalDate,
            status: String = "OPEN",
            effectiveFrom: LocalDate = LocalDate.now(),
            createdBy: String = "system",
        ): BillingPeriodEffective = BillingPeriodEffective(
            periodId = periodId,
            accountId = accountId,
            startDate = startDate,
            endDate = endDate,
            dueDate = dueDate,
            status = status,
            effectiveFrom = effectiveFrom,
            effectiveTo = LocalDate.of(9999, 12, 31),
            systemFrom = Instant.now(),
            systemTo = Instant.parse("9999-12-31T23:59:59Z"),
            createdBy = createdBy,
            modifiedBy = createdBy,
            versionSequence = 1,
        )
    }
}
