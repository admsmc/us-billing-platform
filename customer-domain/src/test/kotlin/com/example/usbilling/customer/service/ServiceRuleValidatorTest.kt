package com.example.usbilling.customer.service

import com.example.usbilling.customer.model.*
import com.example.usbilling.shared.UtilityId
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Tests for service rule validation logic.
 */
class ServiceRuleValidatorTest {

    private val validator = ServiceRuleValidator()
    private val utilityId = UtilityId("UTIL-001")

    @Test
    fun `validates valid service combination`() {
        val rules = ServiceRuleSet(
            utilityId = utilityId,
            dependencies = listOf(ServiceDependency.wastewaterRequiresWater()),
            minimumServices = 1,
        )

        val result = validator.validate(
            proposedServices = setOf(ServiceType.ELECTRIC, ServiceType.WATER, ServiceType.WASTEWATER),
            rules = rules,
        )

        assertTrue(result is ServiceRuleValidationResult.Valid)
    }

    @Test
    fun `rejects wastewater without water dependency`() {
        val rules = ServiceRuleSet(
            utilityId = utilityId,
            dependencies = listOf(ServiceDependency.wastewaterRequiresWater()),
            minimumServices = 1,
        )

        val result = validator.validate(
            proposedServices = setOf(ServiceType.ELECTRIC, ServiceType.WASTEWATER),
            rules = rules,
        )

        assertTrue(result is ServiceRuleValidationResult.Invalid)
        val invalid = result as ServiceRuleValidationResult.Invalid
        assertEquals(1, invalid.violations.size)
        assertEquals(ViolationType.MISSING_DEPENDENCY, invalid.violations[0].type)
        assertTrue(invalid.violations[0].message.contains("Wastewater"))
        assertTrue(invalid.violations[0].message.contains("Water"))
    }

    @Test
    fun `enforces minimum service count`() {
        val rules = ServiceRuleSet(
            utilityId = utilityId,
            minimumServices = 2,
        )

        val result = validator.validate(
            proposedServices = setOf(ServiceType.ELECTRIC),
            rules = rules,
        )

        assertTrue(result is ServiceRuleValidationResult.Invalid)
        val invalid = result as ServiceRuleValidationResult.Invalid
        assertEquals(ViolationType.BELOW_MINIMUM, invalid.violations[0].type)
    }

    @Test
    fun `enforces maximum service count`() {
        val rules = ServiceRuleSet(
            utilityId = utilityId,
            maximumServices = 2,
        )

        val result = validator.validate(
            proposedServices = setOf(
                ServiceType.ELECTRIC,
                ServiceType.WATER,
                ServiceType.WASTEWATER,
            ),
            rules = rules,
        )

        assertTrue(result is ServiceRuleValidationResult.Invalid)
        val invalid = result as ServiceRuleValidationResult.Invalid
        assertEquals(ViolationType.ABOVE_MAXIMUM, invalid.violations[0].type)
    }

    @Test
    fun `enforces required services`() {
        val rules = ServiceRuleSet(
            utilityId = utilityId,
            requiredServices = setOf(ServiceType.ELECTRIC),
        )

        val result = validator.validate(
            proposedServices = setOf(ServiceType.WATER),
            rules = rules,
        )

        assertTrue(result is ServiceRuleValidationResult.Invalid)
        val invalid = result as ServiceRuleValidationResult.Invalid
        assertEquals(ViolationType.REQUIRED_SERVICE_MISSING, invalid.violations[0].type)
        assertTrue(invalid.violations[0].message.contains("Electric"))
    }

    @Test
    fun `enforces mutual exclusions`() {
        val rules = ServiceRuleSet(
            utilityId = utilityId,
            mutuallyExclusive = listOf(
                MutualExclusionRule(
                    services = setOf(ServiceType.GAS, ServiceType.ELECTRIC),
                    reason = "Cannot have both gas and electric at same property",
                ),
            ),
        )

        val result = validator.validate(
            proposedServices = setOf(ServiceType.GAS, ServiceType.ELECTRIC),
            rules = rules,
        )

        assertTrue(result is ServiceRuleValidationResult.Invalid)
        val invalid = result as ServiceRuleValidationResult.Invalid
        assertEquals(ViolationType.MUTUAL_EXCLUSION, invalid.violations[0].type)
    }

    @Test
    fun `enforces allowed combinations whitelist`() {
        val rules = ServiceRuleSet(
            utilityId = utilityId,
            allowedCombinations = listOf(
                ServiceCombination.electricOnly(),
                ServiceCombination.waterServices(),
            ),
        )

        // Valid combination
        val validResult = validator.validate(
            proposedServices = setOf(ServiceType.ELECTRIC),
            rules = rules,
        )
        assertTrue(validResult is ServiceRuleValidationResult.Valid)

        // Invalid combination (not in whitelist)
        val invalidResult = validator.validate(
            proposedServices = setOf(ServiceType.ELECTRIC, ServiceType.REFUSE),
            rules = rules,
        )
        assertTrue(invalidResult is ServiceRuleValidationResult.Invalid)
        val invalid = invalidResult as ServiceRuleValidationResult.Invalid
        assertEquals(ViolationType.COMBINATION_NOT_ALLOWED, invalid.violations[0].type)
    }

    @Test
    fun `validateAdd accepts valid addition`() {
        val rules = ServiceRuleSet(
            utilityId = utilityId,
            dependencies = listOf(ServiceDependency.wastewaterRequiresWater()),
            minimumServices = 1,
        )

        val result = validator.validateAdd(
            existingServices = setOf(ServiceType.ELECTRIC, ServiceType.WATER),
            serviceToAdd = ServiceType.WASTEWATER,
            rules = rules,
        )

        assertTrue(result is ServiceRuleValidationResult.Valid)
    }

    @Test
    fun `validateAdd rejects adding service that would violate dependency`() {
        val rules = ServiceRuleSet(
            utilityId = utilityId,
            dependencies = listOf(ServiceDependency.wastewaterRequiresWater()),
        )

        val result = validator.validateAdd(
            existingServices = setOf(ServiceType.ELECTRIC),
            serviceToAdd = ServiceType.WASTEWATER,
            rules = rules,
        )

        assertTrue(result is ServiceRuleValidationResult.Invalid)
    }

    @Test
    fun `validateAdd rejects adding duplicate service`() {
        val rules = ServiceRuleSet(utilityId = utilityId)

        val result = validator.validateAdd(
            existingServices = setOf(ServiceType.ELECTRIC),
            serviceToAdd = ServiceType.ELECTRIC,
            rules = rules,
        )

        assertTrue(result is ServiceRuleValidationResult.Invalid)
        val invalid = result as ServiceRuleValidationResult.Invalid
        assertTrue(invalid.violations[0].message.contains("already active"))
    }

    @Test
    fun `validateRemove accepts valid removal`() {
        val rules = ServiceRuleSet(
            utilityId = utilityId,
            minimumServices = 1,
        )

        val result = validator.validateRemove(
            existingServices = setOf(ServiceType.ELECTRIC, ServiceType.WATER),
            serviceToRemove = ServiceType.WATER,
            rules = rules,
        )

        assertTrue(result is ServiceRuleValidationResult.Valid)
    }

    @Test
    fun `validateRemove rejects removing service that others depend on`() {
        val rules = ServiceRuleSet(
            utilityId = utilityId,
            dependencies = listOf(ServiceDependency.wastewaterRequiresWater()),
        )

        val result = validator.validateRemove(
            existingServices = setOf(ServiceType.WATER, ServiceType.WASTEWATER),
            serviceToRemove = ServiceType.WATER,
            rules = rules,
        )

        assertTrue(result is ServiceRuleValidationResult.Invalid)
        val invalid = result as ServiceRuleValidationResult.Invalid
        assertEquals(ViolationType.MISSING_DEPENDENCY, invalid.violations[0].type)
        assertTrue(invalid.violations[0].message.contains("Cannot remove"))
    }

    @Test
    fun `validateRemove rejects removing required service`() {
        val rules = ServiceRuleSet(
            utilityId = utilityId,
            requiredServices = setOf(ServiceType.ELECTRIC),
            minimumServices = 1,
        )

        val result = validator.validateRemove(
            existingServices = setOf(ServiceType.ELECTRIC, ServiceType.WATER),
            serviceToRemove = ServiceType.ELECTRIC,
            rules = rules,
        )

        assertTrue(result is ServiceRuleValidationResult.Invalid)
    }

    @Test
    fun `validateChange analyzes bulk service changes`() {
        val rules = ServiceRuleSet(
            utilityId = utilityId,
            dependencies = listOf(ServiceDependency.wastewaterRequiresWater()),
            minimumServices = 1,
        )

        val result = validator.validateChange(
            currentServices = setOf(ServiceType.ELECTRIC, ServiceType.WATER),
            newServices = setOf(ServiceType.ELECTRIC, ServiceType.WATER, ServiceType.WASTEWATER),
            rules = rules,
        )

        assertTrue(result.isValid())
        assertEquals(setOf(ServiceType.WASTEWATER), result.servicesAdded)
        assertEquals(emptySet(), result.servicesRemoved)
        assertEquals(setOf(ServiceType.ELECTRIC, ServiceType.WATER), result.servicesRetained)
    }

    @Test
    fun `service rule presets work correctly`() {
        // Standard municipal utility
        val municipalRules = ServiceRulePresets.standardMunicipalUtility(utilityId)
        assertTrue(municipalRules.dependencies.isNotEmpty())
        assertEquals(1, municipalRules.minimumServices)

        // Electric only
        val electricRules = ServiceRulePresets.electricOnly(utilityId)
        assertEquals(setOf(ServiceType.ELECTRIC), electricRules.requiredServices)
        assertNotNull(electricRules.allowedCombinations)

        // Water utility
        val waterRules = ServiceRulePresets.waterUtility(utilityId)
        assertEquals(setOf(ServiceType.WATER), waterRules.requiredServices)

        // Unrestricted
        val unrestrictedRules = ServiceRulePresets.unrestricted(utilityId)
        assertEquals(0, unrestrictedRules.minimumServices)
        assertTrue(unrestrictedRules.dependencies.isEmpty())
    }

    @Test
    fun `multiple validation errors are all reported`() {
        val rules = ServiceRuleSet(
            utilityId = utilityId,
            dependencies = listOf(
                ServiceDependency.wastewaterRequiresWater(),
                ServiceDependency.stormwaterRequiresWater(),
            ),
            requiredServices = setOf(ServiceType.ELECTRIC),
            minimumServices = 2,
        )

        val result = validator.validate(
            proposedServices = setOf(ServiceType.WASTEWATER, ServiceType.STORMWATER),
            rules = rules,
        )

        assertTrue(result is ServiceRuleValidationResult.Invalid)
        val invalid = result as ServiceRuleValidationResult.Invalid

        // Should have multiple violations:
        // 1. Missing ELECTRIC (required)
        // 2. WASTEWATER missing WATER dependency
        // 3. STORMWATER missing WATER dependency
        assertTrue(invalid.violations.size >= 3)
    }
}
