package com.example.usbilling.customer.events

import com.example.usbilling.customer.model.*
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.time.Instant
import java.time.LocalDate

/**
 * Base sealed class for all customer domain events.
 * These events are published to the outbox for downstream consumption.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@type")
@JsonSubTypes(
    JsonSubTypes.Type(value = AccountCreated::class, name = "AccountCreated"),
    JsonSubTypes.Type(value = AccountActivated::class, name = "AccountActivated"),
    JsonSubTypes.Type(value = AccountSuspended::class, name = "AccountSuspended"),
    JsonSubTypes.Type(value = AccountClosed::class, name = "AccountClosed"),
    JsonSubTypes.Type(value = AccountDetailsUpdated::class, name = "AccountDetailsUpdated"),
    JsonSubTypes.Type(value = ServiceConnected::class, name = "ServiceConnected"),
    JsonSubTypes.Type(value = ServiceDisconnected::class, name = "ServiceDisconnected"),
    JsonSubTypes.Type(value = MeterInstalled::class, name = "MeterInstalled"),
    JsonSubTypes.Type(value = MeterRemoved::class, name = "MeterRemoved"),
)
sealed class CustomerEvent {
    abstract val eventId: String
    abstract val utilityId: UtilityId
    abstract val occurredAt: Instant
    abstract val causedBy: String
}

// Account Lifecycle Events

data class AccountCreated(
    override val eventId: String,
    override val utilityId: UtilityId,
    override val occurredAt: Instant,
    override val causedBy: String,
    val accountId: String,
    val customerId: CustomerId,
    val accountNumber: String,
    val accountType: AccountType,
    val holderName: String,
    val holderType: HolderType,
    val billingAddress: BillingAddress,
    val effectiveFrom: LocalDate,
) : CustomerEvent()

data class AccountActivated(
    override val eventId: String,
    override val utilityId: UtilityId,
    override val occurredAt: Instant,
    override val causedBy: String,
    val accountId: String,
    val customerId: CustomerId,
    val activatedAt: LocalDate,
    val reason: String?,
) : CustomerEvent()

data class AccountSuspended(
    override val eventId: String,
    override val utilityId: UtilityId,
    override val occurredAt: Instant,
    override val causedBy: String,
    val accountId: String,
    val customerId: CustomerId,
    val suspendedAt: LocalDate,
    val reason: SuspensionReason,
    val notes: String?,
) : CustomerEvent()

enum class SuspensionReason {
    NON_PAYMENT,
    CUSTOMER_REQUEST,
    REGULATORY_COMPLIANCE,
    FRAUD_INVESTIGATION,
    OTHER,
}

data class AccountClosed(
    override val eventId: String,
    override val utilityId: UtilityId,
    override val occurredAt: Instant,
    override val causedBy: String,
    val accountId: String,
    val customerId: CustomerId,
    val closedAt: LocalDate,
    val reason: ClosureReason,
    val finalBalanceAmount: Long?,
) : CustomerEvent()

enum class ClosureReason {
    CUSTOMER_REQUEST,
    MOVE_OUT,
    SERVICE_TERMINATED,
    ACCOUNT_CONSOLIDATION,
    DECEASED,
    OTHER,
}

data class AccountDetailsUpdated(
    override val eventId: String,
    override val utilityId: UtilityId,
    override val occurredAt: Instant,
    override val causedBy: String,
    val accountId: String,
    val customerId: CustomerId,
    val changeType: AccountChangeType,
    val effectiveFrom: LocalDate,
    val changeReason: ChangeReason,
) : CustomerEvent()

enum class AccountChangeType {
    HOLDER_INFO,
    BILLING_ADDRESS,
    CONTACT_METHOD,
    SERVICE_ADDRESS,
    ACCOUNT_TYPE,
}

// Service Connection Events

data class ServiceConnected(
    override val eventId: String,
    override val utilityId: UtilityId,
    override val occurredAt: Instant,
    override val causedBy: String,
    val connectionId: String,
    val accountId: String,
    val customerId: CustomerId,
    val servicePointId: String,
    val serviceType: ServiceType,
    val connectionDate: LocalDate,
    val meterId: String?,
) : CustomerEvent()

data class ServiceDisconnected(
    override val eventId: String,
    override val utilityId: UtilityId,
    override val occurredAt: Instant,
    override val causedBy: String,
    val connectionId: String,
    val accountId: String,
    val customerId: CustomerId,
    val servicePointId: String,
    val disconnectionDate: LocalDate,
    val reason: DisconnectionReason,
) : CustomerEvent()

enum class DisconnectionReason {
    CUSTOMER_REQUEST,
    NON_PAYMENT,
    MOVE_OUT,
    SERVICE_UPGRADE,
    MAINTENANCE,
    OTHER,
}

// Meter Events

data class MeterInstalled(
    override val eventId: String,
    override val utilityId: UtilityId,
    override val occurredAt: Instant,
    override val causedBy: String,
    val meterId: String,
    val servicePointId: String,
    val meterSerial: String,
    val meterType: MeterType,
    val installDate: LocalDate,
    val accountId: String?,
) : CustomerEvent()

data class MeterRemoved(
    override val eventId: String,
    override val utilityId: UtilityId,
    override val occurredAt: Instant,
    override val causedBy: String,
    val meterId: String,
    val servicePointId: String,
    val meterSerial: String,
    val removalDate: LocalDate,
    val reason: String?,
) : CustomerEvent()
