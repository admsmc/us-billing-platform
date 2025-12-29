package com.example.usbilling.billing.engine

import com.example.usbilling.billing.model.AccountBalance
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
     * @param accountBalance Current account balance before this bill
     * @return Aggregated totals
     */
    fun aggregate(charges: List<ChargeLineItem>, accountBalance: AccountBalance): ChargeAggregation {
        var totalCharges = 0L
        var totalCredits = 0L

        for (charge in charges) {
            if (charge.amount.amount >= 0) {
                totalCharges += charge.amount.amount
            } else {
                totalCredits += -charge.amount.amount
            }
        }

        val amountDue = totalCharges - totalCredits + accountBalance.balance.amount

        return ChargeAggregation(
            totalCharges = Money(totalCharges),
            totalCredits = Money(totalCredits),
            amountDue = Money(amountDue),
        )
    }
}
