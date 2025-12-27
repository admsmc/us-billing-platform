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
        usageType: UsageType
    ): List<ChargeLineItem> {
        return when (tariff) {
            is RateTariff.FlatRate -> applyFlatRate(consumption, tariff, usageType)
            is RateTariff.TieredRate -> applyTieredRate(consumption, tariff, usageType)
            is RateTariff.TimeOfUseRate -> applyTimeOfUseRate(consumption, tariff, usageType)
        }
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
}
