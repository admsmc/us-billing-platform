package com.example.usbilling.hr.http

import com.example.usbilling.customer.model.*
import com.example.usbilling.customer.service.*
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.LocalDate

/**
 * REST API for managing service connections.
 * 
 * Provides endpoints to:
 * - Connect new services
 * - Disconnect existing services
 * - Query active services
 * - Validate service combinations
 */
@RestController
@RequestMapping("/utilities/{utilityId}/accounts/{accountId}")
class ServiceConnectionController(
    private val serviceConnectionManager: ServiceConnectionManager,
    private val serviceConnectionRepository: ServiceConnectionRepository,
    private val serviceRuleRepository: ServiceRuleRepository
) {
    
    /**
     * Get currently active services for an account.
     */
    @GetMapping("/services")
    fun getActiveServices(
        @PathVariable utilityId: String,
        @PathVariable accountId: String,
        @RequestParam(required = false) asOfDate: String?
    ): ResponseEntity<ActiveServicesResponse> {
        val date = asOfDate?.let { LocalDate.parse(it) } ?: LocalDate.now()
        
        val connections = serviceConnectionRepository.findActiveByAccountId(
            accountId = accountId,
            asOfDate = date
        )
        
        val services = connections.map { it.serviceType }.toSet()
        
        return ResponseEntity.ok(
            ActiveServicesResponse(
                accountId = accountId,
                services = services,
                asOfDate = date,
                connections = connections.map { it.toDto() }
            )
        )
    }
    
    /**
     * Connect a new service to an account.
     */
    @PostMapping("/services")
    fun connectService(
        @PathVariable utilityId: String,
        @PathVariable accountId: String,
        @RequestBody request: ConnectServiceApiRequest,
        @RequestHeader("X-Requested-By", required = false) requestedBy: String?
    ): ResponseEntity<ServiceConnectionResponse> {
        // Load service rules for this utility
        val rules = serviceRuleRepository.findByUtilityId(UtilityId(utilityId))
            ?: ServiceRulePresets.standardMunicipalUtility(UtilityId(utilityId))
        
        // Get current active services
        val currentServices = serviceConnectionRepository
            .findActiveByAccountId(accountId, LocalDate.now())
            .map { it.serviceType }
            .toSet()
        
        // Create domain request
        val domainRequest = ConnectServiceRequest(
            utilityId = UtilityId(utilityId),
            customerId = CustomerId(request.customerId),
            accountId = accountId,
            servicePointId = request.servicePointId,
            serviceType = request.serviceType,
            connectionDate = request.connectionDate ?: LocalDate.now(),
            effectiveFrom = request.effectiveFrom,
            meterId = request.meterId,
            reason = request.reason,
            requestedBy = requestedBy ?: "API"
        )
        
        // Execute connection
        val result = serviceConnectionManager.connectService(
            request = domainRequest,
            ruleSet = rules,
            currentServices = currentServices
        )
        
        return when (result) {
            is ServiceConnectionResult.Connected -> {
                // Persist the connection
                serviceConnectionRepository.save(result.connection)
                
                ResponseEntity.status(HttpStatus.CREATED).body(
                    ServiceConnectionResponse(
                        success = true,
                        connection = result.connection.toDto(),
                        message = "Service ${request.serviceType.displayName()} connected successfully"
                    )
                )
            }
            is ServiceConnectionResult.ValidationFailed -> {
                ResponseEntity.badRequest().body(
                    ServiceConnectionResponse(
                        success = false,
                        connection = null,
                        message = "Validation failed: ${result.errorMessage()}",
                        violations = result.violations.map { it.toDto() }
                    )
                )
            }
            else -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build()
        }
    }
    
    /**
     * Disconnect an existing service.
     */
    @DeleteMapping("/services/{serviceType}")
    fun disconnectService(
        @PathVariable utilityId: String,
        @PathVariable accountId: String,
        @PathVariable serviceType: String,
        @RequestBody request: DisconnectServiceApiRequest,
        @RequestHeader("X-Requested-By", required = false) requestedBy: String?
    ): ResponseEntity<ServiceConnectionResponse> {
        val serviceTypeEnum = try {
            ServiceType.valueOf(serviceType.uppercase())
        } catch (e: IllegalArgumentException) {
            return ResponseEntity.badRequest().body(
                ServiceConnectionResponse(
                    success = false,
                    connection = null,
                    message = "Invalid service type: $serviceType"
                )
            )
        }
        
        // Load service rules
        val rules = serviceRuleRepository.findByUtilityId(UtilityId(utilityId))
            ?: ServiceRulePresets.standardMunicipalUtility(UtilityId(utilityId))
        
        // Get current active services
        val activeConnections = serviceConnectionRepository
            .findActiveByAccountId(accountId, LocalDate.now())
        
        val currentServices = activeConnections.map { it.serviceType }.toSet()
        
        // Find the existing connection for this service
        val existingConnection = activeConnections.find { it.serviceType == serviceTypeEnum }
            ?: return ResponseEntity.notFound().build()
        
        // Create domain request
        val domainRequest = DisconnectServiceRequest(
            utilityId = UtilityId(utilityId),
            customerId = CustomerId(request.customerId),
            accountId = accountId,
            serviceType = serviceTypeEnum,
            disconnectionDate = request.disconnectionDate ?: LocalDate.now(),
            effectiveTo = request.effectiveTo,
            reason = request.reason,
            requestedBy = requestedBy ?: "API"
        )
        
        // Execute disconnection
        val result = serviceConnectionManager.disconnectService(
            request = domainRequest,
            ruleSet = rules,
            currentServices = currentServices,
            existingConnection = existingConnection
        )
        
        return when (result) {
            is ServiceConnectionResult.Disconnected -> {
                // Update the connection in repository
                serviceConnectionRepository.save(result.connection)
                
                ResponseEntity.ok(
                    ServiceConnectionResponse(
                        success = true,
                        connection = result.connection.toDto(),
                        message = "Service ${serviceTypeEnum.displayName()} disconnected successfully"
                    )
                )
            }
            is ServiceConnectionResult.ValidationFailed -> {
                ResponseEntity.badRequest().body(
                    ServiceConnectionResponse(
                        success = false,
                        connection = null,
                        message = "Validation failed: ${result.errorMessage()}",
                        violations = result.violations.map { it.toDto() }
                    )
                )
            }
            else -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build()
        }
    }
    
    /**
     * Validate a proposed service combination without actually making changes.
     */
    @PostMapping("/services/validate")
    fun validateServiceCombination(
        @PathVariable utilityId: String,
        @PathVariable accountId: String,
        @RequestBody request: ValidateServicesRequest
    ): ResponseEntity<ValidationResponse> {
        val rules = serviceRuleRepository.findByUtilityId(UtilityId(utilityId))
            ?: ServiceRulePresets.standardMunicipalUtility(UtilityId(utilityId))
        
        val validator = ServiceRuleValidator()
        val result = validator.validate(request.services, rules)
        
        return ResponseEntity.ok(
            ValidationResponse(
                valid = result is ServiceRuleValidationResult.Valid,
                services = request.services,
                violations = when (result) {
                    is ServiceRuleValidationResult.Invalid -> result.violations.map { it.toDto() }
                    is ServiceRuleValidationResult.Valid -> emptyList()
                }
            )
        )
    }
    
    /**
     * Get service rules for a utility.
     */
    @GetMapping("/service-rules")
    fun getServiceRules(
        @PathVariable utilityId: String
    ): ResponseEntity<ServiceRuleSetDto> {
        val rules = serviceRuleRepository.findByUtilityId(UtilityId(utilityId))
            ?: ServiceRulePresets.standardMunicipalUtility(UtilityId(utilityId))
        
        return ResponseEntity.ok(rules.toDto())
    }
}

// API Request/Response DTOs

data class ConnectServiceApiRequest(
    val customerId: String,
    val servicePointId: String,
    val serviceType: ServiceType,
    val connectionDate: LocalDate? = null,
    val effectiveFrom: LocalDate? = null,
    val meterId: String? = null,
    val reason: String? = null
)

data class DisconnectServiceApiRequest(
    val customerId: String,
    val disconnectionDate: LocalDate? = null,
    val effectiveTo: LocalDate? = null,
    val reason: DisconnectionReason
)

data class ValidateServicesRequest(
    val services: Set<ServiceType>
)

data class ActiveServicesResponse(
    val accountId: String,
    val services: Set<ServiceType>,
    val asOfDate: LocalDate,
    val connections: List<ServiceConnectionDto>
)

data class ServiceConnectionResponse(
    val success: Boolean,
    val connection: ServiceConnectionDto? = null,
    val message: String,
    val violations: List<ServiceRuleViolationDto> = emptyList()
)

data class ValidationResponse(
    val valid: Boolean,
    val services: Set<ServiceType>,
    val violations: List<ServiceRuleViolationDto>
)

data class ServiceConnectionDto(
    val connectionId: String,
    val accountId: String,
    val servicePointId: String,
    val serviceType: ServiceType,
    val connectionDate: LocalDate,
    val disconnectionDate: LocalDate?,
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate
)

data class ServiceRuleViolationDto(
    val type: ViolationType,
    val message: String,
    val affectedServices: Set<ServiceType>
)

data class ServiceRuleSetDto(
    val utilityId: String,
    val dependencies: List<ServiceDependencyDto>,
    val requiredServices: Set<ServiceType>,
    val minimumServices: Int,
    val maximumServices: Int?
)

data class ServiceDependencyDto(
    val dependentService: ServiceType,
    val requiredService: ServiceType,
    val reason: String
)

// Extension functions for DTO conversion

fun ServiceConnection.toDto() = ServiceConnectionDto(
    connectionId = connectionId,
    accountId = accountId,
    servicePointId = servicePointId,
    serviceType = inferServiceTypeFromServicePoint(), // Would need to look this up
    connectionDate = connectionDate,
    disconnectionDate = disconnectionDate,
    effectiveFrom = effectiveFrom,
    effectiveTo = effectiveTo
)

fun ServiceConnection.inferServiceTypeFromServicePoint(): ServiceType {
    // Placeholder - would query service_point table to get service_type
    // For now, return ELECTRIC as default
    return ServiceType.ELECTRIC
}

fun ServiceRuleViolation.toDto() = ServiceRuleViolationDto(
    type = type,
    message = message,
    affectedServices = affectedServices
)

fun ServiceRuleSet.toDto() = ServiceRuleSetDto(
    utilityId = utilityId.value,
    dependencies = dependencies.map { it.toDto() },
    requiredServices = requiredServices,
    minimumServices = minimumServices,
    maximumServices = maximumServices
)

fun ServiceDependency.toDto() = ServiceDependencyDto(
    dependentService = dependentService,
    requiredService = requiredService,
    reason = reason
)

// Repository interfaces (to be implemented)

interface ServiceConnectionRepository {
    fun findActiveByAccountId(accountId: String, asOfDate: LocalDate): List<ServiceConnection>
    fun save(connection: ServiceConnection): ServiceConnection
}

interface ServiceRuleRepository {
    fun findByUtilityId(utilityId: UtilityId): ServiceRuleSet?
    fun save(ruleSet: ServiceRuleSet): ServiceRuleSet
}
