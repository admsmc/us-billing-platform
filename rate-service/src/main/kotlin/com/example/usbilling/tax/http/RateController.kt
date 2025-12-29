package com.example.usbilling.tax.http

import com.example.usbilling.billing.model.RateContext
import com.example.usbilling.shared.UtilityId
import com.example.usbilling.tax.domain.*
import com.example.usbilling.tax.repository.*
import com.example.usbilling.tax.service.RateContextService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.*

@RestController
@RequestMapping("/utilities/{utilityId}")
class RateController(
    private val rateContextService: RateContextService,
    private val rateTariffRepository: RateTariffRepository,
    private val rateComponentRepository: RateComponentRepository,
    private val tariffRegulatoryChargeRepository: TariffRegulatoryChargeRepository,
) {

    /**
     * Get rate context (implements port interface via HTTP).
     * E2E test expects GET /utilities/{utilityId}/rate-context
     */
    @GetMapping("/rate-context")
    fun getRateContext(
        @PathVariable utilityId: String,
        @RequestParam state: String,
        @RequestParam(required = false) asOfDate: String?,
    ): ResponseEntity<RateContext> {
        val date = asOfDate?.let { LocalDate.parse(it) } ?: LocalDate.now()

        val context = rateContextService.getRateContext(
            UtilityId(utilityId),
            date,
            state,
        )

        return ResponseEntity.ok(context)
    }

    /**
     * List all tariffs for a utility.
     */
    @GetMapping("/tariffs")
    fun listTariffs(
        @PathVariable utilityId: String,
        @RequestParam(required = false) asOfDate: String?,
    ): ResponseEntity<List<RateTariffEntity>> {
        val date = asOfDate?.let { LocalDate.parse(it) } ?: LocalDate.now()
        val tariffs = rateTariffRepository.findActiveByUtilityAndDate(utilityId, date)
        return ResponseEntity.ok(tariffs)
    }

    /**
     * Get tariff details including components.
     */
    @GetMapping("/tariffs/{tariffId}")
    fun getTariffDetails(
        @PathVariable utilityId: String,
        @PathVariable tariffId: String,
    ): ResponseEntity<TariffDetailsResponse> {
        val tariff = rateTariffRepository.findById(tariffId).orElse(null)
            ?: return ResponseEntity.notFound().build()

        if (tariff.utilityId != utilityId) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }

        val components = rateComponentRepository.findByTariffId(tariffId)
        val regCharges = tariffRegulatoryChargeRepository.findByTariffId(tariffId)

        return ResponseEntity.ok(TariffDetailsResponse(tariff, components, regCharges))
    }

    /**
     * Create a new tariff.
     */
    @PostMapping("/tariffs")
    fun createTariff(
        @PathVariable utilityId: String,
        @RequestBody request: CreateTariffRequest,
    ): ResponseEntity<RateTariffEntity> {
        val tariffId = UUID.randomUUID().toString()

        val tariffEntity = RateTariffEntity(
            tariffId = tariffId,
            utilityId = utilityId,
            tariffName = request.tariffName,
            tariffCode = request.tariffCode,
            rateStructure = request.rateStructure,
            utilityServiceType = request.utilityServiceType,
            customerClass = request.customerClass,
            effectiveDate = request.effectiveDate,
            expiryDate = request.expiryDate,
            active = true,
            readinessToServeCents = request.readinessToServeCents,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

        val saved = rateTariffRepository.save(tariffEntity)
        return ResponseEntity.status(HttpStatus.CREATED).body(saved)
    }

    /**
     * Add a rate component to a tariff.
     */
    @PostMapping("/tariffs/{tariffId}/components")
    fun addRateComponent(
        @PathVariable utilityId: String,
        @PathVariable tariffId: String,
        @RequestBody request: AddRateComponentRequest,
    ): ResponseEntity<RateComponentEntity> {
        // Verify tariff exists and belongs to utility
        val tariff = rateTariffRepository.findById(tariffId).orElse(null)
            ?: return ResponseEntity.notFound().build()

        if (tariff.utilityId != utilityId) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }

        val componentId = UUID.randomUUID().toString()

        val componentEntity = RateComponentEntity(
            componentId = componentId,
            tariffId = tariffId,
            chargeType = request.chargeType,
            rateValueCents = request.rateValueCents,
            threshold = request.threshold,
            touPeriod = request.touPeriod,
            season = request.season,
            componentOrder = request.componentOrder ?: 0,
            createdAt = Instant.now(),
        )

        val saved = rateComponentRepository.save(componentEntity)
        return ResponseEntity.status(HttpStatus.CREATED).body(saved)
    }

    /**
     * Add a regulatory charge to a tariff.
     */
    @PostMapping("/tariffs/{tariffId}/regulatory-charges")
    fun addRegulatoryCharge(
        @PathVariable utilityId: String,
        @PathVariable tariffId: String,
        @RequestBody request: AddRegulatoryChargeRequest,
    ): ResponseEntity<TariffRegulatoryChargeEntity> {
        // Verify tariff exists and belongs to utility
        val tariff = rateTariffRepository.findById(tariffId).orElse(null)
            ?: return ResponseEntity.notFound().build()

        if (tariff.utilityId != utilityId) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }

        val chargeId = UUID.randomUUID().toString()

        val chargeEntity = TariffRegulatoryChargeEntity(
            chargeId = chargeId,
            tariffId = tariffId,
            chargeCode = request.chargeCode,
            chargeDescription = request.chargeDescription,
            calculationType = request.calculationType,
            rateValueCents = request.rateValueCents,
            createdAt = Instant.now(),
        )

        val saved = tariffRegulatoryChargeRepository.save(chargeEntity)
        return ResponseEntity.status(HttpStatus.CREATED).body(saved)
    }
}

// Request/Response DTOs

data class CreateTariffRequest(
    val tariffName: String,
    val tariffCode: String,
    val rateStructure: String,
    val utilityServiceType: String,
    val customerClass: String?,
    val effectiveDate: LocalDate,
    val expiryDate: LocalDate?,
    val readinessToServeCents: Int,
)

data class AddRateComponentRequest(
    val chargeType: String,
    val rateValueCents: Int,
    val threshold: BigDecimal?,
    val touPeriod: String?,
    val season: String?,
    val componentOrder: Int?,
)

data class AddRegulatoryChargeRequest(
    val chargeCode: String,
    val chargeDescription: String,
    val calculationType: String,
    val rateValueCents: Int,
)

data class TariffDetailsResponse(
    val tariff: RateTariffEntity,
    val components: List<RateComponentEntity>,
    val regulatoryCharges: List<TariffRegulatoryChargeEntity>,
)
