package com.example.usbilling.hr.api

import com.example.usbilling.billing.model.BillingPeriod
import com.example.usbilling.billing.model.CustomerSnapshot
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import java.time.LocalDate

/**
 * Boundary interfaces exposed by the Customer service.
 * These interfaces are implemented in customer-service and consumed by orchestrator/worker services.
 *
 * Note: Originally ported from HR service (payroll domain), now adapted for billing.
 */

/** Provides effective-dated customer snapshots for billing calculations. */
interface CustomerSnapshotProvider {
    /**
     * Returns a customer snapshot as of the given date, suitable for a billing period.
     */
    fun getCustomerSnapshot(utilityId: UtilityId, customerId: CustomerId, asOfDate: LocalDate): CustomerSnapshot?
}

/** Provides billing periods and schedules for a utility. */
interface BillingPeriodProvider {
    /**
     * Returns the billing period identified by [billingPeriodId] for [utilityId], or null if not found.
     */
    fun getBillingPeriod(utilityId: UtilityId, billingPeriodId: String): BillingPeriod?

    /**
     * Returns the active billing period for a given bill date, if any.
     */
    fun findBillingPeriodByBillDate(utilityId: UtilityId, billDate: LocalDate): BillingPeriod?
}
