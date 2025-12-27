package com.example.usbilling.tax.persistence

import com.example.usbilling.shared.UtilityId
import com.example.usbilling.tax.api.TaxQuery
import com.example.usbilling.tax.impl.TaxRuleRecord
import com.example.usbilling.tax.impl.TaxRuleRepository
import com.example.usbilling.tax.support.H2TaxTestSupport
import com.example.usbilling.tax.support.H2TaxTestSupport.H2TaxRuleRepository
import org.jooq.DSLContext
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for locality selection semantics in TaxRuleRepository.
 *
 * These tests assert that:
 * - When TaxQuery.localJurisdictions is empty, NO local rules are selected
 *   (only rules with NULL locality_filter are eligible).
 * - When locality codes are provided, only locals whose locality_filter is in
 *   the requested set are selected (plus generic rules).
 *
 * Scenarios are exercised for both NYC and Michigan city locals.
 */
class LocalitySelectionSemanticsTest {

    private fun createDslContext(dbName: String): DSLContext = H2TaxTestSupport.createDslContext(dbName)

    private fun importConfig(dsl: DSLContext, resourcePath: String) {
        H2TaxTestSupport.importConfigFromResource(dsl, resourcePath, javaClass.classLoader)
    }

    private fun rulesFor(dsl: DSLContext, employerId: UtilityId, residentState: String?, workState: String?, locals: List<String>): List<TaxRuleRecord> {
        val repo: TaxRuleRepository = H2TaxRuleRepository(dsl)
        val query = TaxQuery(
            employerId = employerId,
            asOfDate = LocalDate.of(2025, 3, 31),
            residentState = residentState,
            workState = workState,
            localJurisdictions = locals,
        )
        return repo.findRulesFor(query)
    }

    @Test
    fun `NYC locals are only selected when NYC locality is requested`() {
        val dsl = createDslContext("taxdb-locality-nyc")
        importConfig(dsl, "tax-config/example-federal-2025.json")

        val employerId = UtilityId("EMP-LOCALITY-NYC")

        // A. No locality codes -> no LOCAL rules.
        val rulesNoLocals = rulesFor(
            dsl = dsl,
            employerId = employerId,
            residentState = "NY",
            workState = "NY",
            locals = emptyList(),
        )
        assertTrue(rulesNoLocals.none { it.jurisdictionType.name == "LOCAL" })

        // B. NYC explicitly requested -> NYC local rule is present.
        val rulesWithNyc = rulesFor(
            dsl = dsl,
            employerId = employerId,
            residentState = "NY",
            workState = "NY",
            locals = listOf("NYC"),
        )
        assertTrue(rulesWithNyc.any { it.jurisdictionType.name == "LOCAL" && it.jurisdictionCode == "NYC" })

        // C. Unknown locality code -> no LOCAL rules.
        val rulesWithUnknown = rulesFor(
            dsl = dsl,
            employerId = employerId,
            residentState = "NY",
            workState = "NY",
            locals = listOf("UNKNOWN_CITY"),
        )
        assertTrue(rulesWithUnknown.none { it.jurisdictionType.name == "LOCAL" })
    }

    @Test
    fun `Michigan locals are only selected when matching locality is requested`() {
        val dsl = createDslContext("taxdb-locality-mi")
        importConfig(dsl, "tax-config/mi-locals-2025.json")

        val employerId = UtilityId("EMP-LOCALITY-MI")

        // A. No locality codes -> no LOCAL rules.
        val rulesNoLocals = rulesFor(
            dsl = dsl,
            employerId = employerId,
            residentState = "MI",
            workState = "MI",
            locals = emptyList(),
        )
        assertTrue(rulesNoLocals.none { it.jurisdictionType.name == "LOCAL" })

        // B. DETROIT requested -> Detroit local rule present, other MI locals absent.
        val rulesWithDetroit = rulesFor(
            dsl = dsl,
            employerId = employerId,
            residentState = "MI",
            workState = "MI",
            locals = listOf("DETROIT"),
        )
        val detroitLocals = rulesWithDetroit.filter { it.jurisdictionType.name == "LOCAL" }
        assertEquals(setOf("MI_DETROIT"), detroitLocals.map { it.jurisdictionCode }.toSet())

        // C. Unknown locality code -> no LOCAL rules.
        val rulesWithUnknown = rulesFor(
            dsl = dsl,
            employerId = employerId,
            residentState = "MI",
            workState = "MI",
            locals = listOf("UNKNOWN_MI_CITY"),
        )
        assertTrue(rulesWithUnknown.none { it.jurisdictionType.name == "LOCAL" })
    }
}
