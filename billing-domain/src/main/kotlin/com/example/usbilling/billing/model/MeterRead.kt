package com.example.usbilling.billing.model

import java.time.Instant
import java.time.LocalDate

/**
 * A single meter reading captured at a specific point in time.
 * 
 * @property meterId Unique identifier for the physical meter
 * @property serviceType Type of utility service being metered
 * @property readingValue The cumulative meter reading value
 * @property readDate Date the reading was taken
 * @property usageUnit Unit of measurement
 * @property multiplier Meter multiplier for demand meters (default 1.0)
 * @property readingType Type of read (ACTUAL, ESTIMATED, CUSTOMER_PROVIDED)
 */
data class MeterRead(
    val meterId: String,
    val serviceType: ServiceType,
    val readingValue: Double,
    val readDate: LocalDate,
    val usageUnit: UsageUnit,
    val multiplier: Double = 1.0,
    val readingType: ReadingType = ReadingType.ACTUAL
)

/**
 * How the meter reading was obtained.
 */
enum class ReadingType {
    /** Physical meter read by utility personnel or AMI/AMR system */
    ACTUAL,
    
    /** Estimated based on historical usage patterns */
    ESTIMATED,
    
    /** Customer self-reported reading */
    CUSTOMER_PROVIDED
}
