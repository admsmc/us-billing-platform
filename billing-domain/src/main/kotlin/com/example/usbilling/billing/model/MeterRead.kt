package com.example.usbilling.billing.model

import java.time.Instant

/**
 * A single meter reading captured at a specific point in time.
 * 
 * @property meterId Unique identifier for the physical meter
 * @property usageType Type of utility being metered
 * @property readingValue The cumulative meter reading value
 * @property timestamp When the reading was captured
 * @property unit Unit of measurement (e.g., "kWh", "CCF", "gallons")
 * @property readingType Type of read (ACTUAL, ESTIMATED, CUSTOMER_PROVIDED)
 */
data class MeterRead(
    val meterId: String,
    val usageType: UsageType,
    val readingValue: Double,
    val timestamp: Instant,
    val unit: String,
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
