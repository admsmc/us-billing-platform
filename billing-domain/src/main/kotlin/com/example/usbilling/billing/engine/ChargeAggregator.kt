package com.example.usbilling.billing.engine

import com.example.usbilling.billing.model.ChargeLineItem
import com.example.usbilling.shared.Money

/**
 * Aggregates charges and calculates billing totals.
 */
object ChargeAggregator {
    
    /**
     * Aggregate all charges and calculate totals.
     *
     * @param charges All charge line items
     * @param priorBalance Balance from previous billing periods
     * @return Aggregated totals
     */
    fun aggregate(charges: List<ChargeLineItem>, priorBalance: Money): ChargeAggregation {
        var totalCharges = 0L
        var totalCredits = 0L
        
        for (charge in charges) {
            if (charge.amount.amount >= 0) {
                totalCharges += charge.amount.amount
            } else {
                totalCredits += -charge.amount.amount
            }
        }
        
        val amountDue = totalCharges - totalCredits + priorBalance.amount
        
        return ChargeAggregation(
            totalCharges = Money(totalCharges),
            totalCredits = Money(totalCredits),
            amountDue = Money(amountDue)
        )
    }
}
