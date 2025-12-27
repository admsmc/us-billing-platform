package com.example.usbilling.payroll.model.garnishment

import com.example.usbilling.payroll.model.Percent
import com.example.usbilling.shared.Money
import kotlin.test.Test
import kotlin.test.assertEquals

class SupportCapsTest {

    private val ccpaOnlyParams = SupportCapParams(
        maxRateWhenSupportingOthers = Percent(0.50),
        maxRateWhenNotSupportingOthers = Percent(0.60),
        arrearsBonusRate = Percent(0.05),
        stateAggregateCapRate = null,
    )

    private val michiganParams = ccpaOnlyParams.copy(
        stateAggregateCapRate = Percent(0.50),
    )

    @Test
    fun `CCPA-only cap reflects 50 or 60 percent plus arrears bonus`() {
        val disposable = Money(2_000_00L)

        // Supports other dependents, no 12-week arrears -> 50%
        val ctx1 = SupportCapContext(
            params = ccpaOnlyParams,
            supportsOtherDependents = true,
            arrearsAtLeast12Weeks = false,
        )
        val cap1 = computeSupportCap(disposable, ctx1)
        assertEquals(1_000_00L, cap1.amount)

        // Not supporting others, no 12-week arrears -> 60%
        val ctx2 = SupportCapContext(
            params = ccpaOnlyParams,
            supportsOtherDependents = false,
            arrearsAtLeast12Weeks = false,
        )
        val cap2 = computeSupportCap(disposable, ctx2)
        assertEquals(1_200_00L, cap2.amount)

        // Not supporting others, with >=12-week arrears -> 65%
        val ctx3 = SupportCapContext(
            params = ccpaOnlyParams,
            supportsOtherDependents = false,
            arrearsAtLeast12Weeks = true,
        )
        val cap3 = computeSupportCap(disposable, ctx3)
        assertEquals(1_300_00L, cap3.amount)
    }

    @Test
    fun `Michigan-style cap applies stricter 50 percent aggregate limit`() {
        val disposable = Money(2_000_00L)

        // Case where CCPA would allow 65%, but MI aggregate cap is 50%.
        val ctx = SupportCapContext(
            params = michiganParams,
            supportsOtherDependents = false,
            arrearsAtLeast12Weeks = true,
            jurisdictionCode = "MI",
        )

        val cap = computeSupportCap(disposable, ctx)
        // CCPA-only would be 1,300.00; MI aggregate cap is 1,000.00.
        assertEquals(1_000_00L, cap.amount)
    }
}
