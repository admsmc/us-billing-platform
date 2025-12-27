package com.example.usbilling.billing.engine

import com.example.usbilling.billing.model.*
import com.example.usbilling.shared.Money

/**
 * Applies rate tariffs to consumption to generate usage charges.
 */
object RateApplier {
    
    /**
     * Apply a rate tariff to consumption and generate charge line items.
     *
     * @param consumption Amount of consumption in units
     * @param tariff Rate structure to apply
     * @param usageUnit Unit of measurement for this consumption
     * @param serviceType Type of service being billed (optional for display names)
     * @return List of charge line items for this consumption
     */
    fun applyRate(
        consumption: Double,
        tariff: RateTariff,
        usageUnit: UsageUnit,
        serviceType: ServiceType? = null,
        demandKw: Double? = null
    ): List<ChargeLineItem> {
        val charges = when (tariff) {
            is RateTariff.FlatRate -> applyFlatRate(consumption, tariff, usageUnit, serviceType)
            is RateTariff.TieredRate -> applyTieredRate(consumption, tariff, usageUnit, serviceType)
            is RateTariff.TimeOfUseRate -> applyTimeOfUseRate(consumption, tariff, usageUnit, serviceType)
            is RateTariff.DemandRate -> applyDemandRate(consumption, demandKw ?: 0.0, tariff, usageUnit, serviceType)
        }
        
        // Add regulatory surcharges
        val regulatoryCharges = applyRegulatoryCharges(
            baseCharges = charges,
            surcharges = tariff.getRegulatoryCharges(),
            consumption = consumption,
            usageUnit = usageUnit
        )
        
        return charges + regulatoryCharges
    }
    
    private fun applyFlatRate(
        consumption: Double,
        tariff: RateTariff.FlatRate,
        usageUnit: UsageUnit,
        serviceType: ServiceType?
    ): List<ChargeLineItem> {
        val chargeAmount = Money((consumption * tariff.ratePerUnit.amount).toLong())
        val serviceName = serviceType?.displayName() ?: "Usage"
        
        return listOf(
            ChargeLineItem(
                code = "USAGE",
                description = "$serviceName Usage Charge",
                amount = chargeAmount,
                usageAmount = consumption,
                usageUnit = tariff.unit,
                rate = tariff.ratePerUnit,
                category = ChargeCategory.USAGE_CHARGE,
                serviceType = serviceType
            )
        )
    }
    
    private fun applyTieredRate(
        consumption: Double,
        tariff: RateTariff.TieredRate,
        usageUnit: UsageUnit,
        serviceType: ServiceType?
    ): List<ChargeLineItem> {
        val charges = mutableListOf<ChargeLineItem>()
        var remainingConsumption = consumption
        val serviceName = serviceType?.displayName() ?: "Usage"
        
        for ((tierIndex, tier) in tariff.tiers.withIndex()) {
            if (remainingConsumption <= 0.0) break
            
            val tierConsumption = if (tier.maxUsage != null) {
                minOf(remainingConsumption, tier.maxUsage)
            } else {
                remainingConsumption
            }
            
            val tierCharge = Money((tierConsumption * tier.ratePerUnit.amount).toLong())
            
            charges.add(
                ChargeLineItem(
                    code = "TIER${tierIndex + 1}",
                    description = "$serviceName Usage - Tier ${tierIndex + 1}",
                    amount = tierCharge,
                    usageAmount = tierConsumption,
                    usageUnit = tariff.unit,
                    rate = tier.ratePerUnit,
                    category = ChargeCategory.USAGE_CHARGE,
                    serviceType = serviceType
                )
            )
            
            remainingConsumption -= tierConsumption
        }
        
        return charges
    }
    
    private fun applyTimeOfUseRate(
        consumption: Double,
        tariff: RateTariff.TimeOfUseRate,
        usageUnit: UsageUnit,
        serviceType: ServiceType?
    ): List<ChargeLineItem> {
        // Simplified: assume 50% peak, 50% off-peak for now
        // In real implementation, would use actual hourly consumption data
        val peakConsumption = consumption * 0.5
        val offPeakConsumption = consumption * 0.5
        val serviceName = serviceType?.displayName() ?: "Usage"
        
        val peakCharge = Money((peakConsumption * tariff.peakRate.amount).toLong())
        val offPeakCharge = Money((offPeakConsumption * tariff.offPeakRate.amount).toLong())
        
        return listOf(
            ChargeLineItem(
                code = "PEAK",
                description = "$serviceName Peak Usage",
                amount = peakCharge,
                usageAmount = peakConsumption,
                usageUnit = tariff.unit,
                rate = tariff.peakRate,
                category = ChargeCategory.USAGE_CHARGE,
                serviceType = serviceType
            ),
            ChargeLineItem(
                code = "OFFPEAK",
                description = "$serviceName Off-Peak Usage",
                amount = offPeakCharge,
                usageAmount = offPeakConsumption,
                usageUnit = tariff.unit,
                rate = tariff.offPeakRate,
                category = ChargeCategory.USAGE_CHARGE,
                serviceType = serviceType
            )
        )
    }
    
    private fun applyDemandRate(
        consumption: Double,
        demandKw: Double,
        tariff: RateTariff.DemandRate,
        usageUnit: UsageUnit,
        serviceType: ServiceType?
    ): List<ChargeLineItem> {
        val energyCharge = Money((consumption * tariff.energyRatePerUnit.amount).toLong())
        val demandCharge = Money((demandKw * tariff.demandRatePerKw.amount).toLong())
        val serviceName = serviceType?.displayName() ?: "Usage"
        
        return listOf(
            ChargeLineItem(
                code = "ENERGY",
                description = "$serviceName Energy Charge",
                amount = energyCharge,
                usageAmount = consumption,
                usageUnit = tariff.unit,
                rate = tariff.energyRatePerUnit,
                category = ChargeCategory.USAGE_CHARGE,
                serviceType = serviceType
            ),
            ChargeLineItem(
                code = "DEMAND",
                description = "$serviceName Demand Charge",
                amount = demandCharge,
                usageAmount = demandKw,
                usageUnit = "kW",
                rate = tariff.demandRatePerKw,
                category = ChargeCategory.DEMAND_CHARGE,
                serviceType = serviceType
            )
        )
    }
    
    private fun applyRegulatoryCharges(
        baseCharges: List<ChargeLineItem>,
        surcharges: List<RegulatoryCharge>,
        consumption: Double,
        usageUnit: UsageUnit
    ): List<ChargeLineItem> {
        return surcharges.map { surcharge ->
            val amount = when (surcharge.calculationType) {
                RegulatoryChargeType.FIXED -> surcharge.rate
                
                RegulatoryChargeType.PER_UNIT -> {
                    Money((consumption * surcharge.rate.amount).toLong())
                }
                
                RegulatoryChargeType.PERCENTAGE_OF_ENERGY -> {
                    val energyTotal = baseCharges
                        .filter { it.category == ChargeCategory.USAGE_CHARGE }
                        .sumOf { it.amount.amount }
                    Money((energyTotal * surcharge.rate.amount / 10000).toLong())
                }
                
                RegulatoryChargeType.PERCENTAGE_OF_TOTAL -> {
                    val total = baseCharges.sumOf { it.amount.amount }
                    Money((total * surcharge.rate.amount / 10000).toLong())
                }
            }
            
            ChargeLineItem(
                code = surcharge.code,
                description = surcharge.description,
                amount = amount,
                usageAmount = null,
                usageUnit = null,
                rate = surcharge.rate,
                category = ChargeCategory.REGULATORY_CHARGE
            )
        }
    }
}
