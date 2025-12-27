package com.example.usbilling.billing.model

/**
 * Types of utility services that can be metered and billed.
 */
enum class UsageType {
    /** Electric service (measured in kWh) */
    ELECTRIC,
    
    /** Natural gas service (measured in CCF or therms) */
    GAS,
    
    /** Water service (measured in gallons or CCF) */
    WATER,
    
    /** Sewer/wastewater service (often calculated from water usage) */
    SEWER,
    
    /** Stormwater drainage service */
    STORMWATER,
    
    /** Refuse/trash collection */
    REFUSE
}
