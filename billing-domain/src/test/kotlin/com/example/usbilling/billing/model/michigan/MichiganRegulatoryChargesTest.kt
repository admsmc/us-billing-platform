package com.example.usbilling.billing.model.michigan

import com.example.usbilling.billing.model.RegulatorySurchargeCalculation
import com.example.usbilling.billing.model.ServiceType
import com.example.usbilling.shared.Money
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for Michigan regulatory surcharge definitions.
 */
class MichiganRegulatoryChargesTest {
    
    @Test
    fun `power supply cost recovery surcharge is correctly configured`() {
        val pscr = MichiganElectricSurcharges.powerSupplyCostRecovery()
        
        assertEquals("PSCR", pscr.code)
        assertTrue(pscr.appliesTo(ServiceType.ELECTRIC))
        assertEquals(RegulatorySurchargeCalculation.PER_UNIT, pscr.calculationType)
        assertEquals(125, pscr.ratePerUnit?.amount) // $0.00125/kWh default
    }
    
    @Test
    fun `system access fee is correctly configured`() {
        val saf = MichiganElectricSurcharges.systemAccessFee()
        
        assertEquals("SAF", saf.code)
        assertTrue(saf.appliesTo(ServiceType.ELECTRIC))
        assertEquals(RegulatorySurchargeCalculation.FIXED, saf.calculationType)
        assertEquals(700, saf.fixedAmount?.amount) // $7.00
    }
    
    @Test
    fun `liheap surcharge is correctly configured`() {
        val liheap = MichiganElectricSurcharges.liheapSurcharge()
        
        assertEquals("LIHEAP", liheap.code)
        assertTrue(liheap.appliesTo(ServiceType.ELECTRIC))
        assertEquals(RegulatorySurchargeCalculation.PERCENTAGE_OF_ENERGY, liheap.calculationType)
        assertEquals(0.5, liheap.percentageRate) // 0.5%
    }
    
    @Test
    fun `energy optimization surcharge is correctly configured`() {
        val eo = MichiganElectricSurcharges.energyOptimization()
        
        assertEquals("EO", eo.code)
        assertTrue(eo.appliesTo(ServiceType.ELECTRIC))
        assertEquals(RegulatorySurchargeCalculation.PERCENTAGE_OF_ENERGY, eo.calculationType)
        assertEquals(2.0, eo.percentageRate) // 2%
    }
    
    @Test
    fun `renewable energy standard surcharge is correctly configured`() {
        val res = MichiganElectricSurcharges.renewableEnergyStandard()
        
        assertEquals("RES", res.code)
        assertTrue(res.appliesTo(ServiceType.ELECTRIC))
        assertEquals(RegulatorySurchargeCalculation.PER_UNIT, res.calculationType)
        assertEquals(50, res.ratePerUnit?.amount) // $0.0005/kWh
    }
    
    @Test
    fun `water infrastructure charge is correctly configured`() {
        val infra = MichiganWaterSurcharges.infrastructureCharge()
        
        assertEquals("INFRA", infra.code)
        assertTrue(infra.appliesTo(ServiceType.WATER))
        assertEquals(RegulatorySurchargeCalculation.PERCENTAGE_OF_TOTAL, infra.calculationType)
        assertEquals(2.0, infra.percentageRate) // 2%
    }
    
    @Test
    fun `lead service line replacement charge is correctly configured`() {
        val lslr = MichiganWaterSurcharges.leadServiceLineReplacement()
        
        assertEquals("LSLR", lslr.code)
        assertTrue(lslr.appliesTo(ServiceType.WATER))
        assertEquals(RegulatorySurchargeCalculation.FIXED, lslr.calculationType)
        assertEquals(300, lslr.fixedAmount?.amount) // $3.00
    }
    
    @Test
    fun `stormwater management charge is correctly configured`() {
        val storm = MichiganWaterSurcharges.stormwaterManagement()
        
        assertEquals("STORM", storm.code)
        assertTrue(storm.appliesTo(ServiceType.WASTEWATER))
        assertEquals(RegulatorySurchargeCalculation.FIXED, storm.calculationType)
        assertEquals(500, storm.fixedAmount?.amount) // $5.00
    }
    
    @Test
    fun `MichiganStandardSurcharges forElectric returns electric surcharges`() {
        val electric = MichiganStandardSurcharges.forElectric()
        
        assertTrue(electric.all { it.appliesTo(ServiceType.ELECTRIC) })
        assertEquals(4, electric.size) // PSCR, SAF, LIHEAP, EO
    }
    
    @Test
    fun `MichiganStandardSurcharges forWater returns water surcharges`() {
        val water = MichiganStandardSurcharges.forWater()
        
        assertTrue(water.all { it.appliesTo(ServiceType.WATER) })
        assertEquals(2, water.size) // INFRA, LSLR
    }
    
    @Test
    fun `MichiganStandardSurcharges forWastewater returns wastewater surcharges`() {
        val wastewater = MichiganStandardSurcharges.forWastewater()
        
        assertTrue(wastewater.all { it.appliesTo(ServiceType.WASTEWATER) })
        assertEquals(2, wastewater.size) // INFRA, STORM
    }
    
    @Test
    fun `MichiganStandardSurcharges all returns all standard surcharges`() {
        val all = MichiganStandardSurcharges.all()
        
        assertTrue(all.size >= 6) // At least PSCR, SAF, LIHEAP, EO, INFRA (x2), LSLR, STORM
        
        val electricCount = all.count { it.appliesTo(ServiceType.ELECTRIC) }
        val waterCount = all.count { it.appliesTo(ServiceType.WATER) }
        val wastewaterCount = all.count { it.appliesTo(ServiceType.WASTEWATER) }
        
        assertTrue(electricCount >= 4)
        assertTrue(waterCount >= 2)
        assertTrue(wastewaterCount >= 2)
    }
}
