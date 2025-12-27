package com.example.usbilling.billing.model.michigan

import com.example.usbilling.billing.model.RegulatorySurcharge
import com.example.usbilling.billing.model.RegulatorySurchargeCalculation
import com.example.usbilling.billing.model.ServiceType
import com.example.usbilling.shared.Money

/**
 * Michigan-specific regulatory surcharges for electric service.
 * Based on Michigan Public Service Commission requirements.
 */
object MichiganElectricSurcharges {
    
    /**
     * Power Supply Cost Recovery (PSCR) factor.
     * Allows utilities to recover fuel and purchased power costs.
     * Rate varies by utility and is adjusted periodically.
     */
    fun powerSupplyCostRecovery(ratePerKwh: Money = Money(125)): RegulatorySurcharge {
        return RegulatorySurcharge(
            code = "PSCR",
            description = "Power Supply Cost Recovery",
            calculationType = RegulatorySurchargeCalculation.PER_UNIT,
            ratePerUnit = ratePerKwh,  // Example: $0.00125 per kWh
            appliesTo = setOf(ServiceType.ELECTRIC)
        )
    }
    
    /**
     * System Access Fee (SAF).
     * Fixed monthly charge to cover distribution system costs.
     */
    fun systemAccessFee(monthlyAmount: Money = Money(700)): RegulatorySurcharge {
        return RegulatorySurcharge(
            code = "SAF",
            description = "System Access Fee",
            calculationType = RegulatorySurchargeCalculation.FIXED,
            fixedAmount = monthlyAmount,  // Example: $7.00 per month
            appliesTo = setOf(ServiceType.ELECTRIC)
        )
    }
    
    /**
     * Low Income Energy Assistance Program (LIHEAP) surcharge.
     * Funds assistance programs for low-income customers.
     */
    fun liheapSurcharge(percentageRate: Double = 0.5): RegulatorySurcharge {
        return RegulatorySurcharge(
            code = "LIHEAP",
            description = "Low Income Energy Assistance",
            calculationType = RegulatorySurchargeCalculation.PERCENTAGE_OF_ENERGY,
            percentageRate = percentageRate,  // Example: 0.5% of energy charges
            appliesTo = setOf(ServiceType.ELECTRIC)
        )
    }
    
    /**
     * Energy Optimization (EO) surcharge.
     * Funds energy efficiency programs.
     */
    fun energyOptimization(percentageRate: Double = 2.0): RegulatorySurcharge {
        return RegulatorySurcharge(
            code = "EO",
            description = "Energy Optimization",
            calculationType = RegulatorySurchargeCalculation.PERCENTAGE_OF_ENERGY,
            percentageRate = percentageRate,  // Example: 2% of energy charges
            appliesTo = setOf(ServiceType.ELECTRIC)
        )
    }
    
    /**
     * Renewable Energy Standard (RES) surcharge.
     * Supports renewable energy development.
     */
    fun renewableEnergyStandard(ratePerKwh: Money = Money(50)): RegulatorySurcharge {
        return RegulatorySurcharge(
            code = "RES",
            description = "Renewable Energy Standard",
            calculationType = RegulatorySurchargeCalculation.PER_UNIT,
            ratePerUnit = ratePerKwh,  // Example: $0.0005 per kWh
            appliesTo = setOf(ServiceType.ELECTRIC)
        )
    }
}

/**
 * Michigan-specific regulatory charges for water and wastewater services.
 */
object MichiganWaterSurcharges {
    
    /**
     * Infrastructure Improvement Charge.
     * Funds water/sewer infrastructure upgrades and repairs.
     */
    fun infrastructureCharge(percentageRate: Double = 2.0): RegulatorySurcharge {
        return RegulatorySurcharge(
            code = "INFRA",
            description = "Infrastructure Improvement",
            calculationType = RegulatorySurchargeCalculation.PERCENTAGE_OF_TOTAL,
            percentageRate = percentageRate,  // Example: 2% of total water/sewer charges
            appliesTo = setOf(ServiceType.WATER, ServiceType.WASTEWATER)
        )
    }
    
    /**
     * Lead Service Line Replacement surcharge.
     * Funds lead pipe replacement programs.
     */
    fun leadServiceLineReplacement(monthlyAmount: Money = Money(300)): RegulatorySurcharge {
        return RegulatorySurcharge(
            code = "LSLR",
            description = "Lead Service Line Replacement",
            calculationType = RegulatorySurchargeCalculation.FIXED,
            fixedAmount = monthlyAmount,  // Example: $3.00 per month
            appliesTo = setOf(ServiceType.WATER)
        )
    }
    
    /**
     * Stormwater Management Fee.
     * Funds stormwater infrastructure and runoff management.
     */
    fun stormwaterManagement(monthlyAmount: Money = Money(500)): RegulatorySurcharge {
        return RegulatorySurcharge(
            code = "STORM",
            description = "Stormwater Management Fee",
            calculationType = RegulatorySurchargeCalculation.FIXED,
            fixedAmount = monthlyAmount,  // Example: $5.00 per month
            appliesTo = setOf(ServiceType.WASTEWATER, ServiceType.STORMWATER)
        )
    }
}

/**
 * Standard Michigan public utility surcharge package.
 * Returns a typical set of regulatory surcharges for a Michigan utility.
 */
object MichiganStandardSurcharges {
    
    /**
     * Get standard electric service surcharges for Michigan.
     */
    fun forElectric(): List<RegulatorySurcharge> {
        return listOf(
            MichiganElectricSurcharges.powerSupplyCostRecovery(),
            MichiganElectricSurcharges.systemAccessFee(),
            MichiganElectricSurcharges.liheapSurcharge(),
            MichiganElectricSurcharges.energyOptimization()
        )
    }
    
    /**
     * Get standard water service surcharges for Michigan.
     */
    fun forWater(): List<RegulatorySurcharge> {
        return listOf(
            MichiganWaterSurcharges.infrastructureCharge(),
            MichiganWaterSurcharges.leadServiceLineReplacement()
        )
    }
    
    /**
     * Get standard wastewater service surcharges for Michigan.
     */
    fun forWastewater(): List<RegulatorySurcharge> {
        return listOf(
            MichiganWaterSurcharges.infrastructureCharge(),
            MichiganWaterSurcharges.stormwaterManagement()
        )
    }
    
    /**
     * Get all surcharges for a multi-service Michigan utility.
     */
    fun all(): List<RegulatorySurcharge> {
        return forElectric() + forWater() + forWastewater()
    }
}
