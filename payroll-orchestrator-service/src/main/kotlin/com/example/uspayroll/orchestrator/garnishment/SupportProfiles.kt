package com.example.uspayroll.orchestrator.garnishment

import com.example.uspayroll.payroll.model.Percent
import com.example.uspayroll.payroll.model.garnishment.GarnishmentOrder
import com.example.uspayroll.payroll.model.garnishment.GarnishmentType
import com.example.uspayroll.payroll.model.garnishment.SupportCapContext
import com.example.uspayroll.payroll.model.garnishment.SupportCapParams

/**
 * Support (child/spousal) cap profiles by jurisdiction.
 *
 * Kept outside payroll-domain so the domain remains jurisdiction-agnostic.
 */
object SupportProfiles {

    private val ccpaOnlyParams = SupportCapParams(
        maxRateWhenSupportingOthers = Percent(0.50),
        maxRateWhenNotSupportingOthers = Percent(0.60),
        arrearsBonusRate = Percent(0.05),
        stateAggregateCapRate = null,
    )

    private val michiganParams = ccpaOnlyParams.copy(
        stateAggregateCapRate = Percent(0.50),
    )

    private val paramsByState: Map<String, SupportCapParams> = mapOf(
        "CA" to ccpaOnlyParams,
        "NV" to ccpaOnlyParams,
        "NY" to ccpaOnlyParams,
        "MI" to michiganParams,
    )

    fun forEmployee(homeState: String, orders: List<GarnishmentOrder>): SupportCapContext? {
        val params = paramsByState[homeState] ?: return null
        val supportOrders = orders.filter { it.type == GarnishmentType.CHILD_SUPPORT }
        if (supportOrders.isEmpty()) return null

        val supportsOtherDependents = supportOrders.any { it.supportsOtherDependents == true }
        val arrearsAtLeast12Weeks = supportOrders.any { it.arrearsAtLeast12Weeks == true }

        return SupportCapContext(
            params = params,
            supportsOtherDependents = supportsOtherDependents,
            arrearsAtLeast12Weeks = arrearsAtLeast12Weeks,
            jurisdictionCode = homeState,
        )
    }
}
