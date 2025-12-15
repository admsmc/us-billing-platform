package com.example.uspayroll.labor.tools

import java.io.InputStreamReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LaborStandardsCsvParserTest {

    @Test
    fun `parse sample CSV and validate CA thresholds`() {
        val stream = javaClass.classLoader.getResourceAsStream("labor-standards-2025.csv")
            ?: error("Missing labor-standards-2025.csv on test classpath")

        val standards = InputStreamReader(stream).use { reader ->
            LaborStandardsCsvParser.parse(reader)
        }

        val ca = standards.firstOrNull { it.stateCode == "CA" }
        assertNotNull(ca, "Expected CA row in labor-standards-2025.csv")

        // 16.50 dollars -> 1650 cents
        assertEquals(1_650L, ca.regularMinimumWageCents)
        assertEquals(1_650L, ca.tippedMinimumCashWageCents)
        assertEquals(0L, ca.maxTipCreditCents)

        assertEquals(40.0, ca.weeklyOvertimeThresholdHours)
        assertEquals(8.0, ca.dailyOvertimeThresholdHours)
        assertEquals(12.0, ca.dailyDoubleTimeThresholdHours)
    }

    @Test
    fun `parse sample CSV and validate additional statewide wage values`() {
        val stream = javaClass.classLoader.getResourceAsStream("labor-standards-2025.csv")
            ?: error("Missing labor-standards-2025.csv on test classpath")

        val standards = InputStreamReader(stream).use { reader ->
            LaborStandardsCsvParser.parse(reader)
        }

        val fl = standards.firstOrNull { it.stateCode == "FL" }
        assertNotNull(fl, "Expected FL row in labor-standards-2025.csv")
        assertEquals(1_300L, fl.regularMinimumWageCents)
        assertEquals(998L, fl.tippedMinimumCashWageCents)
        assertEquals(302L, fl.maxTipCreditCents)

        val wa = standards.firstOrNull { it.stateCode == "WA" }
        assertNotNull(wa, "Expected WA row in labor-standards-2025.csv")
        assertEquals(1_666L, wa.regularMinimumWageCents)
        assertEquals(1_666L, wa.tippedMinimumCashWageCents)
        assertEquals(0L, wa.maxTipCreditCents)

        val al = standards.firstOrNull { it.stateCode == "AL" }
        assertNotNull(al, "Expected AL row in labor-standards-2025.csv")
        assertEquals(null, al.regularMinimumWageCents, "AL regular min wage is blank in the statewide CSV")
        assertEquals(213L, al.tippedMinimumCashWageCents)
        assertEquals(512L, al.maxTipCreditCents)
    }
}
