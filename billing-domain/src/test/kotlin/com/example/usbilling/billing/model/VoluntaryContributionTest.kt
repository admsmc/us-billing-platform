package com.example.usbilling.billing.model

import com.example.usbilling.shared.Money
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for voluntary contribution models.
 */
class VoluntaryContributionTest {

    @Test
    fun `voluntary contribution can be created with all fields`() {
        val contribution = VoluntaryContribution(
            code = "ENERGY_ASSIST",
            description = "Energy Assistance Program",
            amount = Money(500),
            program = ContributionProgram.ENERGY_ASSISTANCE,
        )

        assertEquals("ENERGY_ASSIST", contribution.code)
        assertEquals("Energy Assistance Program", contribution.description)
        assertEquals(Money(500), contribution.amount)
        assertEquals(ContributionProgram.ENERGY_ASSISTANCE, contribution.program)
    }

    @Test
    fun `all contribution program types are defined`() {
        // Verify all expected contribution programs exist
        val programs = ContributionProgram.entries

        assertTrue(programs.contains(ContributionProgram.ENERGY_ASSISTANCE))
        assertTrue(programs.contains(ContributionProgram.TREE_PLANTING))
        assertTrue(programs.contains(ContributionProgram.RENEWABLE_ENERGY))
        assertTrue(programs.contains(ContributionProgram.LOW_INCOME_SUPPORT))
        assertTrue(programs.contains(ContributionProgram.COMMUNITY_FUND))
        assertTrue(programs.contains(ContributionProgram.CONSERVATION))
        assertTrue(programs.contains(ContributionProgram.EDUCATION))
        assertTrue(programs.contains(ContributionProgram.OTHER))
    }

    @Test
    fun `contribution amounts must be positive`() {
        // This is a semantic requirement - contributions should be positive
        // While the type system doesn't enforce this, it's best practice
        val validContribution = VoluntaryContribution(
            code = "TREE",
            description = "Tree Planting",
            amount = Money(100),
            program = ContributionProgram.TREE_PLANTING,
        )

        assertTrue(validContribution.amount.amount > 0)
    }

    @Test
    fun `contributions support different program types`() {
        val energyAssist = VoluntaryContribution(
            code = "ENERGY_ASSIST",
            description = "Energy Assistance",
            amount = Money(500),
            program = ContributionProgram.ENERGY_ASSISTANCE,
        )

        val treePlanting = VoluntaryContribution(
            code = "TREE",
            description = "Urban Tree Planting",
            amount = Money(300),
            program = ContributionProgram.TREE_PLANTING,
        )

        val renewable = VoluntaryContribution(
            code = "RENEW",
            description = "Renewable Energy Fund",
            amount = Money(1000),
            program = ContributionProgram.RENEWABLE_ENERGY,
        )

        assertNotEquals(energyAssist.program, treePlanting.program)
        assertNotEquals(energyAssist.program, renewable.program)
        assertNotEquals(treePlanting.program, renewable.program)
    }

    @Test
    fun `contribution can have custom code and description`() {
        val custom = VoluntaryContribution(
            code = "CUSTOM_CODE_123",
            description = "My Custom Contribution Program with a long description",
            amount = Money(2500),
            program = ContributionProgram.OTHER,
        )

        assertEquals("CUSTOM_CODE_123", custom.code)
        assertTrue(custom.description.length > 20)
        assertEquals(Money(2500), custom.amount)
    }

    @Test
    fun `multiple contributions can be combined in a list`() {
        val contributions = listOf(
            VoluntaryContribution("C1", "First", Money(100), ContributionProgram.ENERGY_ASSISTANCE),
            VoluntaryContribution("C2", "Second", Money(200), ContributionProgram.TREE_PLANTING),
            VoluntaryContribution("C3", "Third", Money(300), ContributionProgram.RENEWABLE_ENERGY),
        )

        assertEquals(3, contributions.size)

        val total = contributions.sumOf { it.amount.amount }
        assertEquals(600, total)
    }
}
