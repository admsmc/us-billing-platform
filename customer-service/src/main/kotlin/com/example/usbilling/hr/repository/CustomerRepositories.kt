package com.example.usbilling.hr.repository

import com.example.usbilling.hr.domain.BillingPeriodEntity
import com.example.usbilling.hr.domain.CustomerEntity
import com.example.usbilling.hr.domain.MeterEntity
import com.example.usbilling.hr.domain.MeterReadEntity
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

/**
 * Repository for customer master records.
 */
@Repository
interface CustomerRepository : CrudRepository<CustomerEntity, String> {
    
    @Query("SELECT * FROM customer WHERE utility_id = :utilityId AND active = true")
    fun findByUtilityId(@Param("utilityId") utilityId: String): List<CustomerEntity>
    
    @Query("SELECT * FROM customer WHERE utility_id = :utilityId AND account_number = :accountNumber")
    fun findByUtilityIdAndAccountNumber(
        @Param("utilityId") utilityId: String,
        @Param("accountNumber") accountNumber: String
    ): CustomerEntity?
}

/**
 * Repository for meter installations.
 */
@Repository
interface MeterRepository : CrudRepository<MeterEntity, String> {
    
    @Query("SELECT * FROM meter WHERE customer_id = :customerId AND active = true")
    fun findActiveByCustomerId(@Param("customerId") customerId: String): List<MeterEntity>
    
    @Query("SELECT * FROM meter WHERE customer_id = :customerId")
    fun findByCustomerId(@Param("customerId") customerId: String): List<MeterEntity>
    
    @Query("SELECT * FROM meter WHERE meter_number = :meterNumber")
    fun findByMeterNumber(@Param("meterNumber") meterNumber: String): MeterEntity?
}

/**
 * Repository for billing periods (cycle windows).
 */
@Repository
interface BillingPeriodRepository : CrudRepository<BillingPeriodEntity, String> {
    
    @Query("SELECT * FROM billing_period WHERE customer_id = :customerId ORDER BY start_date DESC")
    fun findByCustomerId(@Param("customerId") customerId: String): List<BillingPeriodEntity>
    
    @Query("""
        SELECT * FROM billing_period 
        WHERE customer_id = :customerId 
          AND status = 'OPEN'
        ORDER BY start_date DESC
        LIMIT 1
    """)
    fun findCurrentOpenPeriod(@Param("customerId") customerId: String): BillingPeriodEntity?
}

/**
 * Repository for meter readings (usage data points).
 */
@Repository
interface MeterReadRepository : CrudRepository<MeterReadEntity, String> {
    
    @Query("SELECT * FROM meter_read WHERE meter_id = :meterId ORDER BY read_date DESC")
    fun findByMeterId(@Param("meterId") meterId: String): List<MeterReadEntity>
    
    @Query("SELECT * FROM meter_read WHERE billing_period_id = :periodId")
    fun findByBillingPeriodId(@Param("periodId") periodId: String): List<MeterReadEntity>
    
    @Query("""
        SELECT * FROM meter_read 
        WHERE meter_id = :meterId 
          AND read_date >= :startDate 
          AND read_date <= :endDate
        ORDER BY read_date ASC
    """)
    fun findByMeterIdAndDateRange(
        @Param("meterId") meterId: String,
        @Param("startDate") startDate: java.time.LocalDate,
        @Param("endDate") endDate: java.time.LocalDate
    ): List<MeterReadEntity>
    
    @Query("""
        SELECT * FROM meter_read 
        WHERE meter_id = :meterId 
        ORDER BY read_date DESC 
        LIMIT 1
    """)
    fun findLatestByMeterId(@Param("meterId") meterId: String): MeterReadEntity?
}
