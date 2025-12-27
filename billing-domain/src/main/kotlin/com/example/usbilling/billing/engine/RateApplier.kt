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
     * @param usageType Type of utility being billed
     * @return List of charge line items for this consumption
     */
    fun applyRate(
        consumption: Double,
        tariff: RateTariff,
        usageType: UsageType,
        demandKw: Double? = null
    ): List<ChargeLineItem> {
        val charges = when (tariff) {
            is RateTariff.FlatRate -> applyFlatRate(consumption, tariff, usageType)
            is RateTariff.TieredRate -> applyTieredRate(consumption, tariff, usageType)
            is RateTariff.TimeOfUseRate -> applyTimeOfUseRate(consumption, tariff, usageType)
            is RateTariff.DemandRate -> applyDemandRate(consumption, demandKw ?: 0.0, tariff, usageType)
        }
        
        // Add regulatory surcharges
        val regulatoryCharges = applyRegulatoryCharges(
            baseCharges = charges,
            surcharges = tariff.getRegulatoryCharges(),
            consumption = consumption,
            usageType = usageType
        )
        
        return charges + regulatoryCharges
    }
    
    private fun applyFlatRate(
        consumption: Double,
        tariff: RateTariff.FlatRate,
        usageType: UsageType
    ): List<ChargeLineItem> {
        val chargeAmount = Money((consumption * tariff.ratePerUnit.amount).toLong())
        
        return listOf(
            ChargeLineItem(
                code = "${usageType.name}_USAGE",
                description = "${usageType.name.lowercase().replaceFirstChar { it.uppercase() }} Usage Charge",
                amount = chargeAmount,
                usageAmount = consumption,
                usageUnit = tariff.unit,
                rate = tariff.ratePerUnit,
                category = ChargeCategory.USAGE_CHARGE
            )
        )
    }
    
    private fun applyTieredRate(
        consumption: Double,
        tariff: RateTariff.TieredRate,
        usageType: UsageType
    ): List<ChargeLineItem> {
        val charges = mutableListOf<ChargeLineItem>()
        var remainingConsumption = consumption
        
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
                    code = "${usageType.name}_TIER${tierIndex + 1}",
                    description = "${usageType.name.lowercase().replaceFirstChar { it.uppercase() }} Usage - Tier ${tierIndex + 1}",
                    amount = tierCharge,
                    usageAmount = tierConsumption,
                    usageUnit = tariff.unit,
                    rate = tier.ratePerUnit,
                    category = ChargeCategory.USAGE_CHARGE
                )
            )
            
            remainingConsumption -= tierConsumption
        }
        
        return charges
    }
    
    private fun applyTimeOfUseRate(
        consumption: Double,
        tariff: RateTariff.TimeOfUseRate,
        usageType: UsageType
    ): List<ChargeLineItem> {
        // Simplified: assume 50% peak, 50% off-peak for now
        // In real implementation, would use actual hourly consumption data
        val peakConsumption = consumption * 0.5
        val offPeakConsumption = consumption * 0.5
        
        val peakCharge = Money((peakConsumption * tariff.peakRate.amount).toLong())
        val offPeakCharge = Money((offPeakConsumption * tariff.offPeakRate.amount).toLong())
        
        return listOf(
            ChargeLineItem(
                code = "${usageType.name}_PEAK",
                description = "${usageType.name.lowercase().replaceFirstChar { it.uppercase() }} Peak Usage",
                amount = peakCharge,
                usageAmount = peakConsumption,
                usageUnit = tariff.unit,
                rate = tariff.peakRate,
                category = ChargeCategory.USAGE_CHARGE
            ),
            ChargeLineItem(
                code = "${usageType.name}_OFFPEAK",
                description = "${usageType.name.lowercase().replaceFirstChar { it.uppercase() }} Off-Peak Usage",
                amount = offPeakCharge,
                usageAmount = offPeakConsumption,
                usageUnit = tariff.unit,
                rate = tariff.offPeakRate,
                category = ChargeCategory.USAGE_CHARGE
            )
        )
    }
    
    private fun applyDemandRate(
        consumption: Double,
        demandKw: Double,
        tariff: RateTariff.DemandRate,
        usageType: UsageType
    ): List<ChargeLineItem> {
        val energyCharge = Money((consumption * tariff.energyRatePerUnit.amount).toLong())
        val demandCharge = Money((demandKw * tariff.demandRatePerKw.amount).toLong())
        
        return listOf(
            ChargeLineItem(
                code = "${usageType.name}_ENERGY",
                description = "${usageType.name.lowercase().replaceFirstChar { it.uppercase() }} Energy Charge",
                amount = energyCharge,
                usageAmount = consumption,
                usageUnit = tariff.unit,
                rate = tariff.energyRatePerUnit,
                category = ChargeCategory.USAGE_CHARGE
            ),
            ChargeLineItem(
                code = "${usageType.name}_DEMAND",
                description = "${usageType.name.lowercase().replaceFirstChar { it.uppercase() }} Demand Charge",
                amount = demandCharge,
                usageAmount = demandKw,
                usageUnit = "kW",
                rate = tariff.demandRatePerKw,
                category = ChargeCategory.DEMAND_CHARGE
            )
        )
    }
    
    private fun applyRegulatoryCharges(
        baseCharges: List<ChargeLineItem>,
        surcharges: List<RegulatoryCharge>,
        consumption: Double,
        usageType: UsageType
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
