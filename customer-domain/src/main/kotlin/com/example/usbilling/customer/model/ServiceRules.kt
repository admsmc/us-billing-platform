package com.example.usbilling.customer.model

import com.example.usbilling.shared.UtilityId

/**
 * Configurable rules governing which services can be combined, their dependencies,
 * and constraints for a utility.
 *
 * @property utilityId The utility these rules apply to
 * @property dependencies Service dependencies (e.g., wastewater requires water)
 * @property mutuallyExclusive Services that cannot be active simultaneously
 * @property requiredServices Services that must always be present
 * @property minimumServices Minimum number of services required
 * @property maximumServices Maximum number of services allowed (null = unlimited)
 * @property allowedCombinations Explicitly allowed service combinations (null = all allowed by other rules)
 */
data class ServiceRuleSet(
    val utilityId: UtilityId,
    val dependencies: List<ServiceDependency> = emptyList(),
    val mutuallyExclusive: List<MutualExclusionRule> = emptyList(),
    val requiredServices: Set<ServiceType> = emptySet(),
    val minimumServices: Int = 0,
    val maximumServices: Int? = null,
    val allowedCombinations: List<ServiceCombination>? = null,
)

/**
 * Defines a dependency between services.
 *
 * @property dependentService The service that has a dependency
 * @property requiredService The service that must be present
 * @property reason Human-readable explanation of why this dependency exists
 */
data class ServiceDependency(
    val dependentService: ServiceType,
    val requiredService: ServiceType,
    val reason: String,
) {
    companion object {
        /**
         * Common dependency: Wastewater requires water service.
         */
        fun wastewaterRequiresWater() = ServiceDependency(
            dependentService = ServiceType.WASTEWATER,
            requiredService = ServiceType.WATER,
            reason = "Wastewater service requires active water service for measurement",
        )

        /**
         * Common dependency: Stormwater typically requires water service.
         */
        fun stormwaterRequiresWater() = ServiceDependency(
            dependentService = ServiceType.STORMWATER,
            requiredService = ServiceType.WATER,
            reason = "Stormwater fees typically bundled with water service",
        )
    }
}

/**
 * Defines services that cannot coexist.
 *
 * @property services Set of mutually exclusive services
 * @property reason Human-readable explanation
 */
data class MutualExclusionRule(
    val services: Set<ServiceType>,
    val reason: String,
) {
    init {
        require(services.size >= 2) { "Mutual exclusion rule must include at least 2 services" }
    }
}

/**
 * Defines an explicitly allowed combination of services.
 * If allowedCombinations is specified, only these combinations are permitted.
 *
 * @property services Set of services in this allowed combination
 * @property description Human-readable description of this combination
 */
data class ServiceCombination(
    val services: Set<ServiceType>,
    val description: String,
) {
    companion object {
        /**
         * Electric only.
         */
        fun electricOnly() = ServiceCombination(
            services = setOf(ServiceType.ELECTRIC),
            description = "Electric service only",
        )

        /**
         * Full utility bundle.
         */
        fun fullUtilityBundle() = ServiceCombination(
            services = setOf(
                ServiceType.ELECTRIC,
                ServiceType.WATER,
                ServiceType.WASTEWATER,
                ServiceType.REFUSE,
                ServiceType.RECYCLING,
            ),
            description = "Complete utility service bundle",
        )

        /**
         * Water services (water + wastewater).
         */
        fun waterServices() = ServiceCombination(
            services = setOf(ServiceType.WATER, ServiceType.WASTEWATER),
            description = "Water and wastewater services",
        )
    }
}

/**
 * Result of service rule validation.
 */
sealed class ServiceRuleValidationResult {
    /**
     * The proposed service configuration is valid.
     */
    data class Valid(val services: Set<ServiceType>) : ServiceRuleValidationResult()

    /**
     * The proposed service configuration violates one or more rules.
     *
     * @property violations List of rule violations
     */
    data class Invalid(val violations: List<ServiceRuleViolation>) : ServiceRuleValidationResult() {
        fun hasViolations(): Boolean = violations.isNotEmpty()

        /**
         * Get all violation messages as a single string.
         */
        fun violationMessages(): String = violations.joinToString(separator = "; ") { it.message }
    }
}

/**
 * A specific service rule violation.
 *
 * @property type Type of violation
 * @property message Human-readable violation message
 * @property affectedServices Services involved in this violation
 */
data class ServiceRuleViolation(
    val type: ViolationType,
    val message: String,
    val affectedServices: Set<ServiceType>,
)

/**
 * Types of service rule violations.
 */
enum class ViolationType {
    /** Missing required dependency */
    MISSING_DEPENDENCY,

    /** Mutually exclusive services present */
    MUTUAL_EXCLUSION,

    /** Required service is missing */
    REQUIRED_SERVICE_MISSING,

    /** Too few services */
    BELOW_MINIMUM,

    /** Too many services */
    ABOVE_MAXIMUM,

    /** Combination not in allowed list */
    COMBINATION_NOT_ALLOWED,
}

/**
 * Factory for common service rule configurations.
 */
object ServiceRulePresets {
    /**
     * Standard municipal utility rules.
     * - Wastewater requires water
     * - Stormwater requires water
     * - At least one service required
     */
    fun standardMunicipalUtility(utilityId: UtilityId) = ServiceRuleSet(
        utilityId = utilityId,
        dependencies = listOf(
            ServiceDependency.wastewaterRequiresWater(),
            ServiceDependency.stormwaterRequiresWater(),
        ),
        minimumServices = 1,
    )

    /**
     * Electric-only utility rules.
     * - Only electric service allowed
     */
    fun electricOnly(utilityId: UtilityId) = ServiceRuleSet(
        utilityId = utilityId,
        requiredServices = setOf(ServiceType.ELECTRIC),
        allowedCombinations = listOf(ServiceCombination.electricOnly()),
    )

    /**
     * Water utility rules.
     * - Water or water+wastewater allowed
     * - If wastewater, water is required
     */
    fun waterUtility(utilityId: UtilityId) = ServiceRuleSet(
        utilityId = utilityId,
        dependencies = listOf(ServiceDependency.wastewaterRequiresWater()),
        requiredServices = setOf(ServiceType.WATER),
        minimumServices = 1,
    )

    /**
     * No restrictions - any combination allowed.
     */
    fun unrestricted(utilityId: UtilityId) = ServiceRuleSet(
        utilityId = utilityId,
        minimumServices = 0,
    )
}
