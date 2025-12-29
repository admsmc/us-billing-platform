package com.example.usbilling.customer.model

import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import java.time.Instant
import java.time.LocalDate

/**
 * Customer account - the core aggregate in the CIS domain.
 *
 * This follows bitemporal (SCD Type 2) modeling:
 * - Valid time: effective_from/effective_to (when the account attributes were true)
 * - System time: system_from/system_to (when the system knew about this version)
 */
data class CustomerAccount(
    val accountId: String,
    val utilityId: UtilityId,
    val customerId: CustomerId,
    val accountNumber: String,
    val accountType: AccountType,
    val accountStatus: AccountStatus,
    val holder: AccountHolder,
    val serviceAddress: ServiceAddress?,
    val billingAddress: BillingAddress,
    val contactMethods: List<ContactMethod>,

    // Bitemporal fields
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate, // exclusive, use LocalDate.MAX for open-ended
    val systemFrom: Instant,
    val systemTo: Instant, // exclusive, use Instant.MAX for current version

    // Audit fields
    val createdBy: String,
    val modifiedBy: String,
    val versionSequence: Int,
    val changeReason: ChangeReason?,
)

enum class AccountType {
    RESIDENTIAL,
    COMMERCIAL,
    INDUSTRIAL,
    AGRICULTURAL,
}

enum class AccountStatus {
    PENDING_ACTIVATION,
    ACTIVE,
    SUSPENDED,
    CLOSED,
}

enum class ChangeReason {
    CUSTOMER_REQUEST,
    CSR_CORRECTION,
    SYSTEM_UPDATE,
    REGULATORY_REQUIREMENT,
    DATA_QUALITY_FIX,
}

/**
 * Account holder - person or entity responsible for the account.
 */
data class AccountHolder(
    val holderName: String,
    val holderType: HolderType,
    val identityVerified: Boolean,
    val taxId: String?,
    val businessName: String?,
)

enum class HolderType {
    INDIVIDUAL,
    BUSINESS,
    GOVERNMENT,
    NON_PROFIT,
}

/**
 * Physical service location address.
 */
data class ServiceAddress(
    val addressId: String,
    val addressLine1: String,
    val addressLine2: String?,
    val city: String,
    val state: String,
    val postalCode: String,
    val country: String = "US",
    val latitude: Double?,
    val longitude: Double?,
    val validated: Boolean = false,
)

/**
 * Billing correspondence address.
 */
data class BillingAddress(
    val addressLine1: String,
    val addressLine2: String?,
    val city: String,
    val state: String,
    val postalCode: String,
    val country: String = "US",
)

/**
 * Contact method for customer communications.
 */
data class ContactMethod(
    val contactType: ContactType,
    val value: String,
    val isPrimary: Boolean,
    val verified: Boolean,
    val consentForMarketing: Boolean = false,
)

enum class ContactType {
    PHONE,
    EMAIL,
    SMS,
    POSTAL,
}

/**
 * Service point - physical connection point for utility service.
 * Bitemporal model.
 */
data class ServicePoint(
    val servicePointId: String,
    val utilityId: UtilityId,
    val serviceAddress: ServiceAddress,
    val serviceType: ServiceType,
    val connectionStatus: ConnectionStatus,
    val rateClass: String?,

    // Bitemporal fields
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate,
    val systemFrom: Instant,
    val systemTo: Instant,

    // Audit
    val createdBy: String,
    val modifiedBy: String,
    val versionSequence: Int,
)

enum class ServiceType {
    ELECTRIC,
    GAS,
    WATER,
    SEWER,
    WASTEWATER,
}

enum class ConnectionStatus {
    CONNECTED,
    DISCONNECTED,
    PENDING_CONNECTION,
    PENDING_DISCONNECTION,
}

/**
 * Meter - physical device measuring consumption.
 * Bitemporal model.
 */
data class Meter(
    val meterId: String,
    val servicePointId: String,
    val meterSerial: String,
    val meterType: MeterType,
    val manufacturer: String?,
    val model: String?,
    val installDate: LocalDate?,
    val lastReadDate: LocalDate?,
    val meterStatus: MeterStatus,

    // Bitemporal fields
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate,
    val systemFrom: Instant,
    val systemTo: Instant,

    // Audit
    val createdBy: String,
    val modifiedBy: String,
    val versionSequence: Int,
)

enum class MeterType {
    ELECTRIC_ANALOG,
    ELECTRIC_DIGITAL,
    ELECTRIC_SMART,
    GAS_ANALOG,
    GAS_DIGITAL,
    WATER_ANALOG,
    WATER_DIGITAL,
}

enum class MeterStatus {
    ACTIVE,
    INACTIVE,
    RETIRED,
    MAINTENANCE,
}

/**
 * Service connection - links account to service point and meter(s).
 * Bitemporal model.
 */
data class ServiceConnection(
    val connectionId: String,
    val accountId: String,
    val servicePointId: String,
    val meterId: String?,
    val connectionDate: LocalDate,
    val disconnectionDate: LocalDate?,
    val connectionReason: String?,

    // Bitemporal fields
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate,
    val systemFrom: Instant,
    val systemTo: Instant,

    // Audit
    val createdBy: String,
    val modifiedBy: String,
)
