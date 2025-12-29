package com.example.usbilling.billing.model

/**
 * Types of utility services that can be billed.
 */
enum class ServiceType {
    /** Electric/power service */
    ELECTRIC,

    /** Potable water service */
    WATER,

    /** Wastewater/sewer service */
    WASTEWATER,

    /** Internet/broadband service */
    BROADBAND,

    /** Natural gas service */
    GAS,

    /** Refuse/trash collection */
    REFUSE,

    /** Recycling collection */
    RECYCLING,

    /** Stormwater management */
    STORMWATER,

    /** Not a metered service - used for voluntary contributions/donations */
    DONATION,

    ;

    /**
     * Human-readable display name for the service.
     */
    fun displayName(): String = when (this) {
        ELECTRIC -> "Electric"
        WATER -> "Water"
        WASTEWATER -> "Wastewater"
        BROADBAND -> "Broadband"
        GAS -> "Gas"
        REFUSE -> "Refuse Collection"
        RECYCLING -> "Recycling"
        STORMWATER -> "Stormwater"
        DONATION -> "Donation"
    }
}

/**
 * Units of measurement for utility usage.
 */
enum class UsageUnit {
    /** Kilowatt-hours (electricity) */
    KWH,

    /** Kilowatts (demand/capacity) */
    KW,

    /** Hundred cubic feet (water/wastewater/gas) */
    CCF,

    /** Gallons (water/wastewater alternative) */
    GALLONS,

    /** Therms (natural gas) */
    THERMS,

    /** Megabits per second (broadband speed tier) */
    MBPS,

    /** Gigabytes (broadband data cap) */
    GB,

    /** Cubic yards (refuse/recycling) */
    CUBIC_YARDS,

    /** Number of containers/cans */
    CONTAINERS,

    /** For fixed services or donations with no usage measurement */
    NONE,

    ;

    /**
     * Human-readable display name for the unit.
     */
    fun displayName(): String = when (this) {
        KWH -> "kWh"
        KW -> "kW"
        CCF -> "CCF"
        GALLONS -> "gallons"
        THERMS -> "therms"
        MBPS -> "Mbps"
        GB -> "GB"
        CUBIC_YARDS -> "cubic yards"
        CONTAINERS -> "containers"
        NONE -> ""
    }
}

/**
 * Configuration for a service type offered by a utility.
 *
 * @property serviceType The type of service
 * @property enabled Whether this service is currently offered
 * @property defaultUsageUnit The default unit of measurement for this service
 * @property requiresMeter Whether this service requires a physical meter
 */
data class ServiceConfiguration(
    val serviceType: ServiceType,
    val enabled: Boolean,
    val defaultUsageUnit: UsageUnit,
    val requiresMeter: Boolean,
) {
    companion object {
        /**
         * Standard configuration for electric service.
         */
        fun electric() = ServiceConfiguration(
            serviceType = ServiceType.ELECTRIC,
            enabled = true,
            defaultUsageUnit = UsageUnit.KWH,
            requiresMeter = true,
        )

        /**
         * Standard configuration for water service.
         */
        fun water() = ServiceConfiguration(
            serviceType = ServiceType.WATER,
            enabled = true,
            defaultUsageUnit = UsageUnit.CCF,
            requiresMeter = true,
        )

        /**
         * Standard configuration for wastewater service.
         */
        fun wastewater() = ServiceConfiguration(
            serviceType = ServiceType.WASTEWATER,
            enabled = true,
            defaultUsageUnit = UsageUnit.CCF,
            requiresMeter = true,
        )

        /**
         * Standard configuration for broadband service.
         */
        fun broadband() = ServiceConfiguration(
            serviceType = ServiceType.BROADBAND,
            enabled = true,
            defaultUsageUnit = UsageUnit.MBPS,
            requiresMeter = false,
        )

        /**
         * Standard configuration for natural gas service.
         */
        fun gas() = ServiceConfiguration(
            serviceType = ServiceType.GAS,
            enabled = true,
            defaultUsageUnit = UsageUnit.THERMS,
            requiresMeter = true,
        )
    }
}
