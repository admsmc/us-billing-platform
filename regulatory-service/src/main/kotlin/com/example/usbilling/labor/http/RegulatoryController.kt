package com.example.usbilling.labor.http

import com.example.usbilling.billing.model.RegulatoryCharge
import com.example.usbilling.billing.model.RegulatoryContext
import com.example.usbilling.billing.model.ServiceType
import com.example.usbilling.labor.service.RegulatoryContextService
import com.example.usbilling.shared.UtilityId
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.LocalDate

/**
 * REST API for regulatory charges and PUC compliance.
 */
@RestController
@RequestMapping("/utilities/{utilityId}")
class RegulatoryController(
    private val regulatoryContextService: RegulatoryContextService
) {

    /**
     * Get regulatory context (implements port interface via HTTP).
     */
    @GetMapping("/regulatory/context")
    fun getRegulatoryContext(
        @PathVariable utilityId: String,
        @RequestParam jurisdiction: String,
        @RequestParam(required = false) asOfDate: String?
    ): ResponseEntity<RegulatoryContext> {
        val date = asOfDate?.let { LocalDate.parse(it) } ?: LocalDate.now()
        
        val context = regulatoryContextService.getRegulatoryContext(
            UtilityId(utilityId),
            date,
            jurisdiction
        )
        
        return ResponseEntity.ok(context)
    }

    /**
     * Get regulatory charges for a jurisdiction.
     * E2E test expects GET /utilities/{utilityId}/regulatory-charges?state=MI
     */
    @GetMapping("/regulatory-charges")
    fun getRegulatoryCharges(
        @PathVariable utilityId: String,
        @RequestParam state: String,
        @RequestParam(required = false) asOfDate: String?
    ): ResponseEntity<RegulatoryChargesResponse> {
        val date = asOfDate?.let { LocalDate.parse(it) } ?: LocalDate.now()
        
        val context = regulatoryContextService.getRegulatoryContext(
            UtilityId(utilityId),
            date,
            state
        )
        
        return ResponseEntity.ok(
            RegulatoryChargesResponse(
                state = state,
                charges = context.regulatoryCharges
            )
        )
    }
    
    /**
     * Get regulatory charges for a specific service type.
     */
    @GetMapping("/regulatory/charges/by-service")
    fun getChargesForService(
        @PathVariable utilityId: String,
        @RequestParam jurisdiction: String,
        @RequestParam serviceType: String,
        @RequestParam(required = false) asOfDate: String?
    ): ResponseEntity<List<RegulatoryCharge>> {
        val date = asOfDate?.let { LocalDate.parse(it) } ?: LocalDate.now()
        val service = parseServiceType(serviceType)
        
        val charges = regulatoryContextService.getChargesForService(
            UtilityId(utilityId),
            service,
            jurisdiction,
            date
        )
        
        return ResponseEntity.ok(charges)
    }

    /**
     * Get a specific regulatory charge by code.
     */
    @GetMapping("/regulatory/charges/{chargeCode}")
    fun getChargeByCode(
        @PathVariable utilityId: String,
        @PathVariable chargeCode: String,
        @RequestParam jurisdiction: String,
        @RequestParam(required = false) asOfDate: String?
    ): ResponseEntity<RegulatoryCharge> {
        val date = asOfDate?.let { LocalDate.parse(it) } ?: LocalDate.now()
        
        val charge = regulatoryContextService.getChargeByCode(
            chargeCode,
            jurisdiction,
            date
        )
        
        return if (charge != null) {
            ResponseEntity.ok(charge)
        } else {
            ResponseEntity.notFound().build()
        }
    }

    /**
     * List all states with regulatory charge data.
     */
    @GetMapping("/regulatory/states")
    fun listStates(): ResponseEntity<List<StateInfo>> {
        // States supported by InMemoryRegulatoryChargeRepository
        val states = listOf(
            StateInfo("MI", "Michigan", 4),
            StateInfo("OH", "Ohio", 4),
            StateInfo("IL", "Illinois", 4),
            StateInfo("CA", "California", 4),
            StateInfo("NY", "New York", 3)
        )
        
        return ResponseEntity.ok(states)
    }

    private fun parseServiceType(value: String): ServiceType {
        return try {
            ServiceType.valueOf(value.uppercase())
        } catch (e: IllegalArgumentException) {
            ServiceType.ELECTRIC // Default fallback
        }
    }
}

/**
 * Information about a state's regulatory charge catalog.
 */
data class StateInfo(
    val code: String,
    val name: String,
    val chargeCount: Int
)

/**
 * Response containing regulatory charges for a jurisdiction.
 */
data class RegulatoryChargesResponse(
    val state: String,
    val charges: List<RegulatoryCharge>
)
