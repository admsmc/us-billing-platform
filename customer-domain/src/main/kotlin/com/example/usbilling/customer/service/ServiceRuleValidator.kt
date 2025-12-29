package com.example.usbilling.customer.service

import com.example.usbilling.customer.model.*

/**
 * Validates proposed service combinations against configured service rules.
 *
 * This validator is stateless and can be reused across multiple validation requests.
 */
class ServiceRuleValidator {

    /**
     * Validate a proposed set of services against the configured rules.
     *
     * @param proposedServices The set of services to validate
     * @param rules The service rule set to validate against
     * @return Validation result indicating whether the combination is valid
     */
    fun validate(
        proposedServices: Set<ServiceType>,
        rules: ServiceRuleSet,
    ): ServiceRuleValidationResult {
        val violations = mutableListOf<ServiceRuleViolation>()

        // Check minimum services
        if (proposedServices.size < rules.minimumServices) {
            violations.add(
                ServiceRuleViolation(
                    type = ViolationType.BELOW_MINIMUM,
                    message = "At least ${rules.minimumServices} service(s) required, but ${proposedServices.size} provided",
                    affectedServices = proposedServices,
                ),
            )
        }

        // Check maximum services
        if (rules.maximumServices != null && proposedServices.size > rules.maximumServices) {
            violations.add(
                ServiceRuleViolation(
                    type = ViolationType.ABOVE_MAXIMUM,
                    message = "Maximum ${rules.maximumServices} service(s) allowed, but ${proposedServices.size} provided",
                    affectedServices = proposedServices,
                ),
            )
        }

        // Check required services
        for (requiredService in rules.requiredServices) {
            if (requiredService !in proposedServices) {
                violations.add(
                    ServiceRuleViolation(
                        type = ViolationType.REQUIRED_SERVICE_MISSING,
                        message = "${requiredService.displayName()} is required but not present",
                        affectedServices = setOf(requiredService),
                    ),
                )
            }
        }

        // Check dependencies
        for (dependency in rules.dependencies) {
            if (dependency.dependentService in proposedServices &&
                dependency.requiredService !in proposedServices
            ) {
                violations.add(
                    ServiceRuleViolation(
                        type = ViolationType.MISSING_DEPENDENCY,
                        message = "${dependency.dependentService.displayName()} requires ${dependency.requiredService.displayName()}: ${dependency.reason}",
                        affectedServices = setOf(dependency.dependentService, dependency.requiredService),
                    ),
                )
            }
        }

        // Check mutual exclusions
        for (exclusionRule in rules.mutuallyExclusive) {
            val presentExclusiveServices = proposedServices.intersect(exclusionRule.services)
            if (presentExclusiveServices.size > 1) {
                violations.add(
                    ServiceRuleViolation(
                        type = ViolationType.MUTUAL_EXCLUSION,
                        message = "Services ${presentExclusiveServices.joinToString { it.displayName() }} cannot be combined: ${exclusionRule.reason}",
                        affectedServices = presentExclusiveServices,
                    ),
                )
            }
        }

        // Check allowed combinations (if specified)
        if (rules.allowedCombinations != null) {
            val isAllowed = rules.allowedCombinations.any { combination ->
                combination.services == proposedServices
            }

            if (!isAllowed) {
                violations.add(
                    ServiceRuleViolation(
                        type = ViolationType.COMBINATION_NOT_ALLOWED,
                        message = "Service combination ${proposedServices.joinToString { it.displayName() }} is not in the allowed combinations list",
                        affectedServices = proposedServices,
                    ),
                )
            }
        }

        return if (violations.isEmpty()) {
            ServiceRuleValidationResult.Valid(proposedServices)
        } else {
            ServiceRuleValidationResult.Invalid(violations)
        }
    }

    /**
     * Validate adding a new service to an existing set of services.
     *
     * @param existingServices Currently active services
     * @param serviceToAdd The service to add
     * @param rules The service rule set to validate against
     * @return Validation result for the new service combination
     */
    fun validateAdd(
        existingServices: Set<ServiceType>,
        serviceToAdd: ServiceType,
        rules: ServiceRuleSet,
    ): ServiceRuleValidationResult {
        if (serviceToAdd in existingServices) {
            return ServiceRuleValidationResult.Invalid(
                listOf(
                    ServiceRuleViolation(
                        type = ViolationType.COMBINATION_NOT_ALLOWED,
                        message = "${serviceToAdd.displayName()} is already active",
                        affectedServices = setOf(serviceToAdd),
                    ),
                ),
            )
        }

        val proposedServices = existingServices + serviceToAdd
        return validate(proposedServices, rules)
    }

    /**
     * Validate removing a service from an existing set of services.
     *
     * @param existingServices Currently active services
     * @param serviceToRemove The service to remove
     * @param rules The service rule set to validate against
     * @return Validation result for the resulting service combination
     */
    fun validateRemove(
        existingServices: Set<ServiceType>,
        serviceToRemove: ServiceType,
        rules: ServiceRuleSet,
    ): ServiceRuleValidationResult {
        if (serviceToRemove !in existingServices) {
            return ServiceRuleValidationResult.Invalid(
                listOf(
                    ServiceRuleViolation(
                        type = ViolationType.COMBINATION_NOT_ALLOWED,
                        message = "${serviceToRemove.displayName()} is not currently active",
                        affectedServices = setOf(serviceToRemove),
                    ),
                ),
            )
        }

        val proposedServices = existingServices - serviceToRemove

        // Check if any remaining services depend on the service being removed
        val dependentServices = rules.dependencies
            .filter { it.requiredService == serviceToRemove && it.dependentService in proposedServices }

        if (dependentServices.isNotEmpty()) {
            val violations = dependentServices.map { dependency ->
                ServiceRuleViolation(
                    type = ViolationType.MISSING_DEPENDENCY,
                    message = "Cannot remove ${serviceToRemove.displayName()}: ${dependency.dependentService.displayName()} depends on it (${dependency.reason})",
                    affectedServices = setOf(serviceToRemove, dependency.dependentService),
                )
            }
            return ServiceRuleValidationResult.Invalid(violations)
        }

        return validate(proposedServices, rules)
    }

    /**
     * Validate replacing one set of services with another (bulk change).
     *
     * @param currentServices Currently active services
     * @param newServices Proposed new service set
     * @param rules The service rule set to validate against
     * @return Validation result with details about services being added/removed
     */
    fun validateChange(
        currentServices: Set<ServiceType>,
        newServices: Set<ServiceType>,
        rules: ServiceRuleSet,
    ): ServiceChangeValidationResult {
        val servicesAdded = newServices - currentServices
        val servicesRemoved = currentServices - newServices
        val servicesRetained = currentServices.intersect(newServices)

        val validationResult = validate(newServices, rules)

        return ServiceChangeValidationResult(
            validationResult = validationResult,
            servicesAdded = servicesAdded,
            servicesRemoved = servicesRemoved,
            servicesRetained = servicesRetained,
        )
    }
}

/**
 * Result of validating a service change operation.
 *
 * @property validationResult The underlying validation result
 * @property servicesAdded Services being added
 * @property servicesRemoved Services being removed
 * @property servicesRetained Services staying the same
 */
data class ServiceChangeValidationResult(
    val validationResult: ServiceRuleValidationResult,
    val servicesAdded: Set<ServiceType>,
    val servicesRemoved: Set<ServiceType>,
    val servicesRetained: Set<ServiceType>,
) {
    fun isValid(): Boolean = validationResult is ServiceRuleValidationResult.Valid

    fun hasChanges(): Boolean = servicesAdded.isNotEmpty() || servicesRemoved.isNotEmpty()

    fun violations(): List<ServiceRuleViolation> = when (validationResult) {
        is ServiceRuleValidationResult.Invalid -> validationResult.violations
        is ServiceRuleValidationResult.Valid -> emptyList()
    }
}
