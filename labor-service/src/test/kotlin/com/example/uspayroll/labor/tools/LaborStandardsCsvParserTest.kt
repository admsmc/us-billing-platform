package com.example.uspayroll.labor.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import java.io.InputStreamReader

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
}
