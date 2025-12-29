package com.example.usbilling.hr.repository

import com.example.usbilling.hr.domain.BillingPeriodEffective
import com.example.usbilling.hr.domain.CustomerAccountEffective
import com.example.usbilling.hr.domain.MeterEffective
import com.example.usbilling.hr.domain.ServicePointEffective
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate

/**
 * Repository for bitemporal customer accounts using SCD2 pattern.
 * 
 * All queries respect both effective time and system time dimensions.
 * Updates are append-only: new versions are inserted, old versions closed.
 */
@Repository
interface CustomerAccountBitemporalRepository : CrudRepository<CustomerAccountEffective, String> {
    
    /**
     * Find the current version of a customer account as of a specific date.
     * 
     * Current version means:
     * - system_from <= CURRENT_TIMESTAMP AND system_to > CURRENT_TIMESTAMP (system knows about it now)
     * - effective_from <= asOfDate AND effective_to > asOfDate (was valid on that date)
     */
    @Query("""
        SELECT * FROM customer_account_effective
        WHERE account_id = :accountId
          AND system_from <= CURRENT_TIMESTAMP
          AND system_to > CURRENT_TIMESTAMP
          AND effective_from <= :asOfDate
          AND effective_to > :asOfDate
        ORDER BY effective_from DESC, system_from DESC
        LIMIT 1
    """)
    fun findCurrentVersion(
        @Param("accountId") accountId: String,
        @Param("asOfDate") asOfDate: LocalDate = LocalDate.now()
    ): CustomerAccountEffective?
    
    /**
     * Find all versions of a customer account (complete history).
     */
    @Query("""
        SELECT * FROM customer_account_effective
        WHERE account_id = :accountId
        ORDER BY effective_from DESC, system_from DESC
    """)
    fun findAllVersions(@Param("accountId") accountId: String): List<CustomerAccountEffective>
    
    /**
     * Find accounts by utility.
     */
    @Query("""
        SELECT * FROM customer_account_effective
        WHERE utility_id = :utilityId
          AND system_from <= CURRENT_TIMESTAMP
          AND system_to > CURRENT_TIMESTAMP
          AND effective_from <= CURRENT_DATE
          AND effective_to > CURRENT_DATE
        ORDER BY account_id
    """)
    fun findByUtilityId(@Param("utilityId") utilityId: String): List<CustomerAccountEffective>
    
    /**
     * Close the current system version by setting system_to = CURRENT_TIMESTAMP.
     * This is step 1 of the SCD2 update pattern.
     * 
     * IMPORTANT: This executes raw UPDATE SQL - only for closing versions before append.
     */
    @Modifying
    @Query("""
        UPDATE customer_account_effective
        SET system_to = CURRENT_TIMESTAMP
        WHERE account_id = :accountId
          AND system_to = '9999-12-31 23:59:59'::timestamp
    """)
    fun closeCurrentSystemVersion(@Param("accountId") accountId: String): Int
    
    /**
     * Check if an account exists (has any current version).
     */
    @Query("""
        SELECT COUNT(*) > 0 FROM customer_account_effective
        WHERE account_id = :accountId
          AND system_from <= CURRENT_TIMESTAMP
          AND system_to > CURRENT_TIMESTAMP
          AND effective_from <= CURRENT_DATE
          AND effective_to > CURRENT_DATE
    """)
    fun existsByAccountId(@Param("accountId") accountId: String): Boolean
}

/**
 * Repository for bitemporal service points.
 */
@Repository
interface ServicePointBitemporalRepository : CrudRepository<ServicePointEffective, String> {
    
    @Query("""
        SELECT * FROM service_point_effective
        WHERE service_point_id = :servicePointId
          AND system_from <= CURRENT_TIMESTAMP
          AND system_to > CURRENT_TIMESTAMP
          AND effective_from <= :asOfDate
          AND effective_to > :asOfDate
        ORDER BY effective_from DESC, system_from DESC
        LIMIT 1
    """)
    fun findCurrentVersion(
        @Param("servicePointId") servicePointId: String,
        @Param("asOfDate") asOfDate: LocalDate = LocalDate.now()
    ): ServicePointEffective?
    
    /**
     * Find all service points for an account (current versions).
     */
    @Query("""
        SELECT * FROM service_point_effective
        WHERE account_id = :accountId
          AND system_from <= CURRENT_TIMESTAMP
          AND system_to > CURRENT_TIMESTAMP
          AND effective_from <= :asOfDate
          AND effective_to > :asOfDate
        ORDER BY service_point_id
    """)
    fun findByAccountId(
        @Param("accountId") accountId: String,
        @Param("asOfDate") asOfDate: LocalDate
    ): List<ServicePointEffective>
    
    @Modifying
    @Query("""
        UPDATE service_point_effective
        SET system_to = CURRENT_TIMESTAMP
        WHERE service_point_id = :servicePointId
          AND system_to = '9999-12-31 23:59:59'::timestamp
    """)
    fun closeCurrentSystemVersion(@Param("servicePointId") servicePointId: String): Int
}

/**
 * Repository for bitemporal meters.
 */
@Repository
interface MeterBitemporalRepository : CrudRepository<MeterEffective, String> {
    
    @Query("""
        SELECT * FROM meter_effective
        WHERE meter_id = :meterId
          AND system_from <= CURRENT_TIMESTAMP
          AND system_to > CURRENT_TIMESTAMP
          AND effective_from <= :asOfDate
          AND effective_to > :asOfDate
        ORDER BY effective_from DESC, system_from DESC
        LIMIT 1
    """)
    fun findCurrentVersion(
        @Param("meterId") meterId: String,
        @Param("asOfDate") asOfDate: LocalDate = LocalDate.now()
    ): MeterEffective?
    
    /**
     * Find all meters for a service point (current versions).
     */
    @Query("""
        SELECT * FROM meter_effective
        WHERE service_point_id = :servicePointId
          AND system_from <= CURRENT_TIMESTAMP
          AND system_to > CURRENT_TIMESTAMP
          AND effective_from <= :asOfDate
          AND effective_to > :asOfDate
        ORDER BY meter_id
    """)
    fun findByServicePointId(
        @Param("servicePointId") servicePointId: String,
        @Param("asOfDate") asOfDate: LocalDate
    ): List<MeterEffective>
    
    /**
     * Find meters by account via service points.
     */
    @Query("""
        SELECT m.* FROM meter_effective m
        JOIN service_point_effective sp ON m.service_point_id = sp.service_point_id
        WHERE sp.account_id = :accountId
          AND m.system_from <= CURRENT_TIMESTAMP
          AND m.system_to > CURRENT_TIMESTAMP
          AND m.effective_from <= :asOfDate
          AND m.effective_to > :asOfDate
          AND sp.system_from <= CURRENT_TIMESTAMP
          AND sp.system_to > CURRENT_TIMESTAMP
          AND sp.effective_from <= :asOfDate
          AND sp.effective_to > :asOfDate
        ORDER BY m.meter_id
    """)
    fun findByAccountId(
        @Param("accountId") accountId: String,
        @Param("asOfDate") asOfDate: LocalDate
    ): List<MeterEffective>
    
    @Modifying
    @Query("""
        UPDATE meter_effective
        SET system_to = CURRENT_TIMESTAMP
        WHERE meter_id = :meterId
          AND system_to = '9999-12-31 23:59:59'::timestamp
    """)
    fun closeCurrentSystemVersion(@Param("meterId") meterId: String): Int
}

/**
 * Repository for bitemporal billing periods.
 */
@Repository
interface BillingPeriodBitemporalRepository : CrudRepository<BillingPeriodEffective, String> {
    
    @Query("""
        SELECT * FROM billing_period_effective
        WHERE period_id = :periodId
          AND system_from <= CURRENT_TIMESTAMP
          AND system_to > CURRENT_TIMESTAMP
          AND effective_from <= :asOfDate
          AND effective_to > :asOfDate
        ORDER BY effective_from DESC, system_from DESC
        LIMIT 1
    """)
    fun findCurrentVersion(
        @Param("periodId") periodId: String,
        @Param("asOfDate") asOfDate: LocalDate = LocalDate.now()
    ): BillingPeriodEffective?
    
    /**
     * Find billing periods for an account.
     */
    @Query("""
        SELECT * FROM billing_period_effective
        WHERE account_id = :accountId
          AND system_from <= CURRENT_TIMESTAMP
          AND system_to > CURRENT_TIMESTAMP
          AND effective_from <= :asOfDate
          AND effective_to > :asOfDate
        ORDER BY start_date DESC
    """)
    fun findByAccountId(
        @Param("accountId") accountId: String,
        @Param("asOfDate") asOfDate: LocalDate
    ): List<BillingPeriodEffective>
    
    @Modifying
    @Query("""
        UPDATE billing_period_effective
        SET system_to = CURRENT_TIMESTAMP
        WHERE period_id = :periodId
          AND system_to = '9999-12-31 23:59:59'::timestamp
    """)
    fun closeCurrentSystemVersion(@Param("periodId") periodId: String): Int
}
