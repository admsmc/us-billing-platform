package com.example.usbilling.billing.model

import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId

/**
 * Snapshot of customer information at a point in time.
 *
 * Captures all customer-related data needed for billing calculations.
 *
 * @property customerId Unique customer identifier
 * @property utilityId The utility this customer belongs to
 * @property serviceAddress Physical address where service is provided
 * @property billingAddress Address for bill delivery (may differ from service address)
 * @property customerClass Residential, commercial, industrial classification
 * @property accountStatus Current account status
 * @property meters List of meters assigned to this customer
 * @property specialRates Any special rate programs customer is enrolled in
 */
data class CustomerSnapshot(
    val customerId: CustomerId,
    val utilityId: UtilityId,
    val serviceAddress: ServiceAddress,
    val billingAddress: ServiceAddress?,
    val customerClass: CustomerClass,
    val accountStatus: AccountStatus,
    val meters: List<MeterInfo>,
    val specialRates: List<String> = emptyList()
)

/**
 * Physical or mailing address.
 */
data class ServiceAddress(
    val street1: String,
    val street2: String? = null,
    val city: String,
    val state: String,
    val zipCode: String,
    val country: String = "US"
)

/**
 * Customer classification for rate purposes.
 */
enum class CustomerClass {
    /** Single-family residential */
    RESIDENTIAL,
    
    /** Multi-family residential */
    MULTI_FAMILY,
    
    /** Small commercial (< 50 kW demand) */
    SMALL_COMMERCIAL,
    
    /** General commercial */
    COMMERCIAL,
    
    /** Large commercial (> 500 kW demand) */
    LARGE_COMMERCIAL,
    
    /** Industrial customers */
    INDUSTRIAL,
    
    /** Agricultural operations */
    AGRICULTURAL,
    
    /** Government/municipal */
    MUNICIPAL,
    
    /** Schools and universities */
    INSTITUTIONAL
}

/**
 * Current status of customer account.
 */
enum class AccountStatus {
    /** Active account in good standing */
    ACTIVE,
    
    /** Account suspended (non-payment, etc.) */
    SUSPENDED,
    
    /** Service disconnected */
    DISCONNECTED,
    
    /** Account closed */
    CLOSED,
    
    /** New account pending activation */
    PENDING
}

/**
 * Information about a meter assigned to a customer.
 *
 * @property meterId Unique meter identifier
 * @property usageType Type of service metered
 * @property meterType Physical meter type
 * @property installDate When meter was installed
 * @property lastReadDate Most recent meter reading date
 */
data class MeterInfo(
    val meterId: String,
    val usageType: UsageType,
    val meterType: MeterType,
    val installDate: java.time.LocalDate,
    val lastReadDate: java.time.LocalDate?
)

/**
 * Type of meter hardware.
 */
enum class MeterType {
    /** Automated Meter Reading (AMR) - one-way communication */
    AMR,
    
    /** Advanced Metering Infrastructure (AMI) - two-way communication */
    AMI,
    
    /** Traditional analog meter requiring manual reads */
    ANALOG,
    
    /** Smart meter with interval data */
    SMART
}
