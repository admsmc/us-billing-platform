package com.example.usbilling.tax.repository

import com.example.usbilling.tax.domain.*
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
interface RateTariffRepository : CrudRepository<RateTariffEntity, String> {
    
    @Query("""
        SELECT * FROM rate_tariff 
        WHERE utility_id = :utilityId 
          AND active = true
          AND effective_date <= :asOfDate
          AND (expiry_date IS NULL OR expiry_date > :asOfDate)
    """)
    fun findActiveByUtilityAndDate(
        @Param("utilityId") utilityId: String,
        @Param("asOfDate") asOfDate: LocalDate
    ): List<RateTariffEntity>
    
    @Query("""
        SELECT * FROM rate_tariff 
        WHERE utility_id = :utilityId 
          AND utility_service_type = :serviceType
          AND active = true
          AND effective_date <= :asOfDate
          AND (expiry_date IS NULL OR expiry_date > :asOfDate)
        ORDER BY effective_date DESC
        LIMIT 1
    """)
    fun findByUtilityServiceAndDate(
        @Param("utilityId") utilityId: String,
        @Param("serviceType") serviceType: String,
        @Param("asOfDate") asOfDate: LocalDate
    ): RateTariffEntity?
}

@Repository
interface RateComponentRepository : CrudRepository<RateComponentEntity, String> {
    
    @Query("SELECT * FROM rate_component WHERE tariff_id = :tariffId ORDER BY component_order ASC")
    fun findByTariffId(@Param("tariffId") tariffId: String): List<RateComponentEntity>
}

@Repository
interface TouScheduleRepository : CrudRepository<TouScheduleEntity, String> {
    
    @Query("SELECT * FROM tou_schedule WHERE tariff_id = :tariffId")
    fun findByTariffId(@Param("tariffId") tariffId: String): List<TouScheduleEntity>
}

@Repository
interface TariffRegulatoryChargeRepository : CrudRepository<TariffRegulatoryChargeEntity, String> {
    
    @Query("SELECT * FROM tariff_regulatory_charge WHERE tariff_id = :tariffId")
    fun findByTariffId(@Param("tariffId") tariffId: String): List<TariffRegulatoryChargeEntity>
}
