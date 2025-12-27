package com.example.usbilling.hr.garnishment

import com.example.usbilling.persistence.flyway.FlywaySupport
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDate
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GarnishmentOrderOverrideConstraintsTest {

    private fun createJdbcTemplate(): JdbcTemplate {
        val ds = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:hr_garnishment_overrides;DB_CLOSE_DELAY=-1")
            user = "sa"
            password = ""
        }

        FlywaySupport.migrate(
            dataSource = ds,
            "classpath:db/migration",
        )

        return JdbcTemplate(ds)
    }

    @Test
    fun `db constraints reject incomplete typed formula overrides`() {
        val jdbc = createJdbcTemplate()

        // Minimal seed to satisfy FK-less schema expectations.
        jdbc.update(
            """
            INSERT INTO employee (
              employer_id, employee_id, home_state, work_state, work_city,
              filing_status, employment_type,
              hire_date, termination_date,
              dependents,
              federal_withholding_exempt, is_nonresident_alien,
              w4_annual_credit_cents, w4_other_income_cents, w4_deductions_cents,
              w4_step2_multiple_jobs,
              additional_withholding_cents,
              fica_exempt, flsa_enterprise_covered, flsa_exempt_status, is_tipped_employee
            ) VALUES ('EMP', 'EE', 'CA', 'CA', 'SF', 'SINGLE', 'REGULAR', ?, NULL, 0,
                      FALSE, FALSE,
                      NULL, NULL, NULL,
                      FALSE,
                      NULL,
                      FALSE, TRUE, 'NON_EXEMPT', FALSE)
            """.trimIndent(),
            LocalDate.of(2024, 1, 1),
        )

        val threw = runCatching {
            jdbc.update(
                """
                INSERT INTO garnishment_order (
                  employer_id, employee_id, order_id,
                  type, issuing_jurisdiction_type, issuing_jurisdiction_code,
                  case_number, status,
                  served_date, end_date,
                  priority_class, sequence_within_class,
                  formula_type, percent_of_disposable
                ) VALUES ('EMP', 'EE', 'ORDER-BAD',
                          'CREDITOR_GARNISHMENT', 'STATE', 'CA',
                          'CASE', 'ACTIVE',
                          ?, NULL,
                          0, 0,
                          'PERCENT_OF_DISPOSABLE', NULL)
                """.trimIndent(),
                LocalDate.of(2024, 1, 1),
            )
        }.isFailure

        assertTrue(threw, "Expected DB CHECK constraint failure for missing percent_of_disposable")
    }

    @Test
    fun `validator reports friendly errors for inconsistent override rows`() {
        val result = GarnishmentOrderOverrideValidator.validate(
            listOf(
                GarnishmentOrderOverrideValidator.OverrideColumns(
                    orderId = "ORDER1",
                    formulaType = "PERCENT_OF_DISPOSABLE",
                    percentOfDisposable = null,
                    fixedAmountCents = 100L,
                    protectedFloorCents = 100L,
                    protectedMinWageHourlyRateCents = 100L,
                    protectedMinWageHours = null,
                    protectedMinWageMultiplier = null,
                    formulaJson = null,
                    protectedEarningsRuleJson = null,
                ),
            ),
        )

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.message.contains("requires percent_of_disposable") })
        assertTrue(result.errors.any { it.message.contains("must not set fixed_amount_cents") })
        assertTrue(result.errors.any { it.message.contains("not both") })
    }
}
