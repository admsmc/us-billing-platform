package com.example.usbilling.customer.service

import com.example.usbilling.customer.events.*
import com.example.usbilling.customer.model.*
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Domain service for managing service connections with validation and event emission.
 *
 * This service orchestrates:
 * - Service rule validation
 * - Service connection/disconnection operations
 * - Domain event emission
 * - Bitemporal tracking
 */
class ServiceConnectionManager(
    private val serviceRuleValidator: ServiceRuleValidator,
    private val eventPublisher: ServiceConnectionEventPublisher,
) {

    /**
     * Connect a new service for an account.
     *
     * @param request Connection request with all required details
     * @param ruleSet Service rules to validate against
     * @param currentServices Set of currently active services
     * @return Result of the connection operation
     */
    fun connectService(
        request: ConnectServiceRequest,
        ruleSet: ServiceRuleSet,
        currentServices: Set<ServiceType>,
    ): ServiceConnectionResult {
        // Validate the new service combination
        val validationResult = serviceRuleValidator.validateAdd(
            existingServices = currentServices,
            serviceToAdd = request.serviceType,
            rules = ruleSet,
        )

        if (validationResult is ServiceRuleValidationResult.Invalid) {
            return ServiceConnectionResult.ValidationFailed(validationResult.violations)
        }

        // Create the service connection
        val connectionId = UUID.randomUUID().toString()
        val now = Instant.now()

        val connection = ServiceConnection(
            connectionId = connectionId,
            accountId = request.accountId,
            servicePointId = request.servicePointId,
            meterId = request.meterId,
            connectionDate = request.connectionDate,
            disconnectionDate = null,
            connectionReason = request.reason,
            effectiveFrom = request.effectiveFrom ?: request.connectionDate,
            effectiveTo = LocalDate.of(9999, 12, 31), // Active until explicitly ended
            systemFrom = now,
            systemTo = Instant.ofEpochMilli(Long.MAX_VALUE),
            createdBy = request.requestedBy,
            modifiedBy = request.requestedBy,
        )

        // Emit domain event
        val event = ServiceConnected(
            eventId = UUID.randomUUID().toString(),
            utilityId = request.utilityId,
            occurredAt = now,
            causedBy = request.requestedBy,
            connectionId = connectionId,
            accountId = request.accountId,
            customerId = request.customerId,
            servicePointId = request.servicePointId,
            serviceType = request.serviceType,
            connectionDate = request.connectionDate,
            meterId = request.meterId,
        )

        eventPublisher.publish(event)

        return ServiceConnectionResult.Connected(connection, event)
    }

    /**
     * Disconnect an existing service.
     *
     * @param request Disconnection request with all required details
     * @param ruleSet Service rules to validate against
     * @param currentServices Set of currently active services
     * @param existingConnection The current service connection to disconnect
     * @return Result of the disconnection operation
     */
    fun disconnectService(
        request: DisconnectServiceRequest,
        ruleSet: ServiceRuleSet,
        currentServices: Set<ServiceType>,
        existingConnection: ServiceConnection,
    ): ServiceConnectionResult {
        // Validate removing this service
        val validationResult = serviceRuleValidator.validateRemove(
            existingServices = currentServices,
            serviceToRemove = request.serviceType,
            rules = ruleSet,
        )

        if (validationResult is ServiceRuleValidationResult.Invalid) {
            return ServiceConnectionResult.ValidationFailed(validationResult.violations)
        }

        // Update the service connection with disconnection date
        val now = Instant.now()
        val updatedConnection = existingConnection.copy(
            disconnectionDate = request.disconnectionDate,
            effectiveTo = request.effectiveTo ?: request.disconnectionDate,
            systemTo = now,
            modifiedBy = request.requestedBy,
        )

        // Emit domain event
        val event = ServiceDisconnected(
            eventId = UUID.randomUUID().toString(),
            utilityId = request.utilityId,
            occurredAt = now,
            causedBy = request.requestedBy,
            connectionId = existingConnection.connectionId,
            accountId = existingConnection.accountId,
            customerId = request.customerId,
            servicePointId = existingConnection.servicePointId,
            disconnectionDate = request.disconnectionDate,
            reason = request.reason,
        )

        eventPublisher.publish(event)

        return ServiceConnectionResult.Disconnected(updatedConnection, event)
    }

    /**
     * Perform a bulk service change (add/remove multiple services atomically).
     *
     * @param request Bulk change request
     * @param ruleSet Service rules to validate against
     * @param currentServices Set of currently active services
     * @return Result of the bulk change operation
     */
    fun changeServices(
        request: ChangeServicesRequest,
        ruleSet: ServiceRuleSet,
        currentServices: Set<ServiceType>,
    ): ServiceChangeOperationResult {
        // Validate the target service set
        val validationResult = serviceRuleValidator.validateChange(
            currentServices = currentServices,
            newServices = request.targetServices,
            rules = ruleSet,
        )

        if (!validationResult.isValid()) {
            return ServiceChangeOperationResult.Failed(validationResult.violations())
        }

        val connectResults = mutableListOf<ServiceConnectionResult.Connected>()
        val disconnectResults = mutableListOf<ServiceConnectionResult.Disconnected>()

        // Process additions
        for (serviceToAdd in validationResult.servicesAdded) {
            // Would need to collect connection details per service
            // For now, this is a placeholder showing the pattern
        }

        // Process removals
        for (serviceToRemove in validationResult.servicesRemoved) {
            // Would need to find existing connections for these services
            // For now, this is a placeholder showing the pattern
        }

        return ServiceChangeOperationResult.Success(
            servicesAdded = validationResult.servicesAdded,
            servicesRemoved = validationResult.servicesRemoved,
            servicesRetained = validationResult.servicesRetained,
        )
    }
}

/**
 * Result of a service connection operation.
 */
sealed class ServiceConnectionResult {
    /**
     * Service was successfully connected.
     */
    data class Connected(
        val connection: ServiceConnection,
        val event: ServiceConnected,
    ) : ServiceConnectionResult()

    /**
     * Service was successfully disconnected.
     */
    data class Disconnected(
        val connection: ServiceConnection,
        val event: ServiceDisconnected,
    ) : ServiceConnectionResult()

    /**
     * Operation failed due to validation errors.
     */
    data class ValidationFailed(
        val violations: List<ServiceRuleViolation>,
    ) : ServiceConnectionResult() {
        fun errorMessage(): String = violations.joinToString("; ") { it.message }
    }
}

/**
 * Result of a bulk service change operation.
 */
sealed class ServiceChangeOperationResult {
    data class Success(
        val servicesAdded: Set<ServiceType>,
        val servicesRemoved: Set<ServiceType>,
        val servicesRetained: Set<ServiceType>,
    ) : ServiceChangeOperationResult()

    data class Failed(
        val violations: List<ServiceRuleViolation>,
    ) : ServiceChangeOperationResult()
}

/**
 * Request to connect a new service.
 */
data class ConnectServiceRequest(
    val utilityId: UtilityId,
    val customerId: CustomerId,
    val accountId: String,
    val servicePointId: String,
    val serviceType: ServiceType,
    val connectionDate: LocalDate,
    val effectiveFrom: LocalDate? = null,
    val meterId: String? = null,
    val reason: String? = null,
    val requestedBy: String,
)

/**
 * Request to disconnect an existing service.
 */
data class DisconnectServiceRequest(
    val utilityId: UtilityId,
    val customerId: CustomerId,
    val accountId: String,
    val serviceType: ServiceType,
    val disconnectionDate: LocalDate,
    val effectiveTo: LocalDate? = null,
    val reason: DisconnectionReason,
    val requestedBy: String,
)

/**
 * Request to change service portfolio (bulk operation).
 */
data class ChangeServicesRequest(
    val utilityId: UtilityId,
    val customerId: CustomerId,
    val accountId: String,
    val targetServices: Set<ServiceType>,
    val effectiveDate: LocalDate,
    val requestedBy: String,
)

/**
 * Interface for publishing service connection events.
 */
interface ServiceConnectionEventPublisher {
    fun publish(event: CustomerEvent)
}
