package com.example.usbilling.hr.client

import com.example.usbilling.billing.model.BillingPeriod
import com.example.usbilling.billing.model.CustomerSnapshot
import com.example.usbilling.billing.model.MeterReadPair
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import java.time.LocalDate

/**
 * Client-side abstraction for talking to customer-service.
 *
 * This interface is intentionally transport-agnostic (HTTP/gRPC/etc). Concrete
 * adapters live in service modules.
 * 
 * Note: Renamed from HrClient (payroll) to CustomerClient (billing).
 * Garnishment methods removed (payroll-specific).
 */
interface CustomerClient {
    fun getCustomerSnapshot(utilityId: UtilityId, customerId: CustomerId, asOfDate: LocalDate): CustomerSnapshot?

    fun getBillingPeriod(utilityId: UtilityId, billingPeriodId: String): BillingPeriod?

    fun findBillingPeriodByBillDate(utilityId: UtilityId, billDate: LocalDate): BillingPeriod?
    
    /**
     * Get meter reads for a customer within a billing period.
     * This is the billing equivalent of time/hours data in payroll.
     */
    fun getMeterReads(utilityId: UtilityId, customerId: CustomerId, billingPeriodId: String): List<MeterReadPair>
}

// Alias for backward compatibility during migration
@Deprecated("Use CustomerClient instead", ReplaceWith("CustomerClient"))
typealias HrClient = CustomerClient
