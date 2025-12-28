package com.example.usbilling.tax.service

import com.example.usbilling.billing.model.*
import com.example.usbilling.shared.Money
import com.example.usbilling.shared.UtilityId
import com.example.usbilling.tax.api.RateContextProvider
import com.example.usbilling.tax.repository.*
import org.springframework.stereotype.Service
import java.time.LocalDate

/**
 * Rate context service - implements RateContextProvider port.
 *
 * Assembles RateContext from database tariff catalog, mapping tariff entities
 * to appropriate RateTariff domain types (FlatRate, TieredRate, TimeOfUseRate, DemandRate).
 */
@Service
class RateContextService(
    private val rateTariffRepository: RateTariffRepository,
    private val rateComponentRepository: RateComponentRepository,
    private val touScheduleRepository: TouScheduleRepository,
    private val tariffRegulatoryChargeRepository: TariffRegulatoryChargeRepository
) : RateContextProvider {

    override fun getRateContext(utilityId: UtilityId, asOfDate: LocalDate, serviceState: String): RateContext {
        // Find all active tariffs for this utility as of the given date
        val tariffEntities = rateTariffRepository.findActiveByUtilityAndDate(utilityId.value, asOfDate)
        
        // Build map of ServiceType → RateTariff
        val rateSchedules = tariffEntities.mapNotNull { tariffEntity ->
            val serviceType = parseServiceType(tariffEntity.utilityServiceType)
            val rateTariff = buildRateTariff(tariffEntity)
            serviceType to rateTariff
        }.toMap()
        
        // Collect all regulatory charges from all tariffs
        val allRegulatoryCharges = tariffEntities.flatMap { tariffEntity ->
            val chargeEntities = tariffRegulatoryChargeRepository.findByTariffId(tariffEntity.tariffId)
            chargeEntities.map { toRegulatoryCharge(it) }
        }.distinctBy { it.code }
        
        return RateContext(
            utilityId = utilityId,
            serviceState = serviceState,
            rateSchedules = rateSchedules,
            regulatoryCharges = allRegulatoryCharges,
            effectiveDate = asOfDate
        )
    }

    /**
     * Build RateTariff domain object from tariff entity.
     * Maps rate_structure to appropriate sealed class subtype.
     */
    private fun buildRateTariff(tariffEntity: com.example.usbilling.tax.domain.RateTariffEntity): RateTariff {
        val readinessCharge = Money(tariffEntity.readinessToServeCents.toLong())
        val components = rateComponentRepository.findByTariffId(tariffEntity.tariffId)
        val regCharges = tariffRegulatoryChargeRepository.findByTariffId(tariffEntity.tariffId)
            .map { toRegulatoryCharge(it) }
        
        return when (tariffEntity.rateStructure.uppercase()) {
            "FLAT" -> buildFlatRate(tariffEntity, components, regCharges, readinessCharge)
            "TIERED" -> buildTieredRate(tariffEntity, components, regCharges, readinessCharge)
            "TOU" -> buildTimeOfUseRate(tariffEntity, components, regCharges, readinessCharge)
            "DEMAND" -> buildDemandRate(tariffEntity, components, regCharges, readinessCharge)
            else -> {
                // Fallback to flat rate if unknown structure
                buildFlatRate(tariffEntity, components, regCharges, readinessCharge)
            }
        }
    }

    private fun buildFlatRate(
        tariff: com.example.usbilling.tax.domain.RateTariffEntity,
        components: List<com.example.usbilling.tax.domain.RateComponentEntity>,
        regCharges: List<RegulatoryCharge>,
        readinessCharge: Money
    ): RateTariff.FlatRate {
        // Find ENERGY component
        val energyComponent = components.firstOrNull { it.chargeType == "ENERGY" }
        val ratePerUnit = energyComponent?.let { Money(it.rateValueCents.toLong()) } ?: Money(0)
        
        return RateTariff.FlatRate(
            readinessToServeCharge = readinessCharge,
            ratePerUnit = ratePerUnit,
            unit = inferUnit(tariff.utilityServiceType),
            regulatorySurcharges = regCharges
        )
    }

    private fun buildTieredRate(
        tariff: com.example.usbilling.tax.domain.RateTariffEntity,
        components: List<com.example.usbilling.tax.domain.RateComponentEntity>,
        regCharges: List<RegulatoryCharge>,
        readinessCharge: Money
    ): RateTariff.TieredRate {
        // Find TIER components and build RateTier list
        val tierComponents = components.filter { it.chargeType == "TIER" }
            .sortedBy { it.componentOrder }
        
        val tiers = tierComponents.map { component ->
            RateTier(
                maxUsage = component.threshold?.toDouble(),
                ratePerUnit = Money(component.rateValueCents.toLong())
            )
        }
        
        // If no tiers defined, create a single tier
        val finalTiers = if (tiers.isEmpty()) {
            listOf(RateTier(maxUsage = null, ratePerUnit = Money(0)))
        } else {
            tiers
        }
        
        return RateTariff.TieredRate(
            readinessToServeCharge = readinessCharge,
            tiers = finalTiers,
            unit = inferUnit(tariff.utilityServiceType),
            regulatorySurcharges = regCharges
        )
    }

    private fun buildTimeOfUseRate(
        tariff: com.example.usbilling.tax.domain.RateTariffEntity,
        components: List<com.example.usbilling.tax.domain.RateComponentEntity>,
        regCharges: List<RegulatoryCharge>,
        readinessCharge: Money
    ): RateTariff.TimeOfUseRate {
        // Find TOU components
        val peakComponent = components.firstOrNull { it.chargeType == "TOU_PEAK" }
        val offPeakComponent = components.firstOrNull { it.chargeType == "TOU_OFF_PEAK" }
        val shoulderComponent = components.firstOrNull { it.chargeType == "TOU_SHOULDER" }
        
        return RateTariff.TimeOfUseRate(
            readinessToServeCharge = readinessCharge,
            peakRate = peakComponent?.let { Money(it.rateValueCents.toLong()) } ?: Money(0),
            offPeakRate = offPeakComponent?.let { Money(it.rateValueCents.toLong()) } ?: Money(0),
            shoulderRate = shoulderComponent?.let { Money(it.rateValueCents.toLong()) },
            unit = inferUnit(tariff.utilityServiceType),
            regulatorySurcharges = regCharges
        )
    }

    private fun buildDemandRate(
        tariff: com.example.usbilling.tax.domain.RateTariffEntity,
        components: List<com.example.usbilling.tax.domain.RateComponentEntity>,
        regCharges: List<RegulatoryCharge>,
        readinessCharge: Money
    ): RateTariff.DemandRate {
        val energyComponent = components.firstOrNull { it.chargeType == "ENERGY" }
        val demandComponent = components.firstOrNull { it.chargeType == "DEMAND" }
        
        return RateTariff.DemandRate(
            readinessToServeCharge = readinessCharge,
            energyRatePerUnit = energyComponent?.let { Money(it.rateValueCents.toLong()) } ?: Money(0),
            demandRatePerKw = demandComponent?.let { Money(it.rateValueCents.toLong()) } ?: Money(0),
            unit = inferUnit(tariff.utilityServiceType),
            regulatorySurcharges = regCharges
        )
    }

    private fun toRegulatoryCharge(entity: com.example.usbilling.tax.domain.TariffRegulatoryChargeEntity): RegulatoryCharge {
        return RegulatoryCharge(
            code = entity.chargeCode,
            description = entity.chargeDescription,
            calculationType = parseRegulatoryChargeType(entity.calculationType),
            rate = Money(entity.rateValueCents.toLong())
        )
    }

    private fun parseServiceType(value: String): ServiceType {
        return try {
            ServiceType.valueOf(value.uppercase())
        } catch (e: IllegalArgumentException) {
            ServiceType.ELECTRIC
        }
    }

    private fun parseRegulatoryChargeType(value: String): RegulatoryChargeType {
        return try {
            RegulatoryChargeType.valueOf(value.uppercase())
        } catch (e: IllegalArgumentException) {
            RegulatoryChargeType.FIXED
        }
    }

    private fun inferUnit(serviceType: String): String {
        return when (serviceType.uppercase()) {
            "ELECTRIC" -> "kWh"
            "GAS" -> "therms"
            "WATER" -> "CCF"
            "WASTEWATER" -> "CCF"
            else -> "units"
        }
    }
}
