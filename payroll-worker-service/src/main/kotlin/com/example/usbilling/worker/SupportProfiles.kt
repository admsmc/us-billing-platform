package com.example.usbilling.worker

import com.example.usbilling.payroll.model.Percent
import com.example.usbilling.payroll.model.garnishment.GarnishmentOrder
import com.example.usbilling.payroll.model.garnishment.GarnishmentType
import com.example.usbilling.payroll.model.garnishment.SupportCapContext
import com.example.usbilling.payroll.model.garnishment.SupportCapParams

/**
 * Support (child/spousal) cap profiles by jurisdiction. This is kept in
 * worker-service so that the payroll-domain remains jurisdiction-agnostic and
 * receives only parameterized [SupportCapContext] data.
 */
data class SupportJurisdictionProfile(
    val stateCode: String,
    val params: SupportCapParams,
)

object SupportProfiles {

    private val ccpaOnlyParams = SupportCapParams(
        maxRateWhenSupportingOthers = Percent(0.50),
        maxRateWhenNotSupportingOthers = Percent(0.60),
        arrearsBonusRate = Percent(0.05),
        stateAggregateCapRate = null,
    )

    private val michiganProfile = SupportJurisdictionProfile(
        stateCode = "MI",
        params = ccpaOnlyParams.copy(
            // Michigan aggregate support cap (FOC practice) is modeled as a
            // 50% overlay on top of the federal CCPA cap. The raw CCPA cap is
            // still computed (50 / 60 / 65%), but the effective cap for MI
            // employees is min(CCPA cap, 50% of disposable earnings).
            stateAggregateCapRate = Percent(0.50),
        ),
    )

    private val californiaProfile = SupportJurisdictionProfile(
        stateCode = "CA",
        params = ccpaOnlyParams,
    )

    private val nevadaProfile = SupportJurisdictionProfile(
        stateCode = "NV",
        params = ccpaOnlyParams,
    )

    private val newYorkProfile = SupportJurisdictionProfile(
        stateCode = "NY",
        params = ccpaOnlyParams,
    )

    private val profilesByState: Map<String, SupportJurisdictionProfile> =
        mapOf(
            "MI" to michiganProfile,
            "CA" to californiaProfile,
            "NV" to nevadaProfile,
            "NY" to newYorkProfile,
        )

    /**
     * Construct a [SupportCapContext] for a given employee and set of
     * garnishment orders, if applicable. Currently this is keyed solely off the
     * employee's home state; in future we can refine this using HR-provided
     * support metadata.
     */
    fun forEmployee(homeState: String, orders: List<GarnishmentOrder>): SupportCapContext? {
        val profile = profilesByState[homeState] ?: return null
        val supportOrders = orders.filter { it.type == GarnishmentType.CHILD_SUPPORT }
        if (supportOrders.isEmpty()) return null

        val supportsOtherDependents = supportOrders.any { it.supportsOtherDependents == true }
        val arrearsAtLeast12Weeks = supportOrders.any { it.arrearsAtLeast12Weeks == true }

        return SupportCapContext(
            params = profile.params,
            supportsOtherDependents = supportsOtherDependents,
            arrearsAtLeast12Weeks = arrearsAtLeast12Weeks,
            jurisdictionCode = profile.stateCode,
        )
    }
}
