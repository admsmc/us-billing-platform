package com.example.uspayroll.worker

import com.example.uspayroll.hr.HrApplication
import com.example.uspayroll.payroll.model.*
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money
import com.example.uspayroll.shared.PayRunId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.util.TestPropertyValues
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Import
import org.springframework.test.context.ContextConfiguration
import java.time.LocalDate
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Worker–HR integration test focused on MI locals.
 *
 * This test:
 * - Starts a real hr-service (H2) with an employee working in "Detroit".
 * - Uses a capturing TaxClient implementation to observe locality codes passed
 *   by PayrollRunService when running an HR-backed pay period.
 * - Asserts that the derived locality code list is ["DETROIT"] for the MI
 *   employee, proving that workCity from HR flows through LocalityResolver and
 *   into TaxClient.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ContextConfiguration(initializers = [MichiganLocalityHrWorkerIntegrationTest.Companion.Initializer::class])
@TestInstance(Lifecycle.PER_CLASS)
@Import(MichiganLocalityHrWorkerIntegrationTest.TestTaxConfig::class)
class MichiganLocalityHrWorkerIntegrationTest {

    companion object {
        lateinit var hrContext: ConfigurableApplicationContext

        class Initializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
            override fun initialize(context: ConfigurableApplicationContext) {
                // Start real hr-service on a fixed test port with H2.
                hrContext = SpringApplicationBuilder(HrApplication::class.java)
                    .properties(
                        mapOf(
                            "server.port" to "8086",
                            "spring.datasource.url" to "jdbc:h2:mem:hr_mi_local;DB_CLOSE_DELAY=-1",
                            "spring.datasource.driverClassName" to "org.h2.Driver",
                            "spring.datasource.username" to "sa",
                            "spring.datasource.password" to "",
                            "spring.flyway.locations" to "classpath:db/migration",
                        ),
                    )
                    .run()

                // Seed HR data with an MI employee working in Detroit.
                val dataSource = hrContext.getBean(DataSource::class.java)
                dataSource.connection.use { conn ->
                    fun java.sql.Connection.exec(sql: String, vararg args: Any?) {
                        prepareStatement(sql).use { ps ->
                            args.forEachIndexed { index, arg ->
                                ps.setObject(index + 1, arg)
                            }
                            ps.executeUpdate()
                        }
                    }

                    val employerId = "EMP-HR-MI-LOCAL"
                    val employeeId = "EE-MI-DETROIT"
                    val payPeriodId = "2025-01-BW-MI"
                    val checkDate = LocalDate.of(2025, 1, 15)

                    conn.exec("DELETE FROM employment_compensation")
                    conn.exec("DELETE FROM employee")
                    conn.exec("DELETE FROM pay_period")

                    conn.exec(
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
                        ) VALUES (?, ?, 'MI', 'MI', 'Detroit', 'SINGLE', 'REGULAR', ?, NULL, 0,
                                  FALSE, FALSE,
                                  NULL, NULL, NULL,
                                  FALSE,
                                  NULL,
                                  FALSE, TRUE, 'NON_EXEMPT', FALSE)
                        """.trimIndent(),
                        employerId,
                        employeeId,
                        LocalDate.of(2024, 6, 1),
                    )

                    conn.exec(
                        """
                        INSERT INTO employment_compensation (
                          employer_id, employee_id,
                          effective_from, effective_to,
                          compensation_type,
                          annual_salary_cents, hourly_rate_cents, pay_frequency
                        ) VALUES (?, ?, ?, ?, 'SALARIED', 100_00000, NULL, 'BIWEEKLY')
                        """.trimIndent(),
                        employerId,
                        employeeId,
                        LocalDate.of(2024, 6, 1),
                        LocalDate.of(9999, 12, 31),
                    )

                    conn.exec(
                        """
                        INSERT INTO pay_period (
                          employer_id, id,
                          start_date, end_date, check_date,
                          frequency, sequence_in_year
                        ) VALUES (?, ?, ?, ?, ?, 'BIWEEKLY', 1)
                        """.trimIndent(),
                        employerId,
                        payPeriodId,
                        LocalDate.of(2025, 1, 1),
                        LocalDate.of(2025, 1, 14),
                        checkDate,
                    )
                }

                // Point worker's HrClient at the real hr-service.
                TestPropertyValues.of("hr.base-url=http://localhost:8086").applyTo(context.environment)
            }
        }
    }

    class CapturingTaxClient : com.example.uspayroll.worker.client.TaxClient {
        val capturedLocalityCodes = mutableListOf<List<String>>()

        override fun getTaxContext(
            employerId: EmployerId,
            asOfDate: LocalDate,
            localityCodes: List<String>,
        ): TaxContext {
            capturedLocalityCodes += localityCodes
            // Return an empty TaxContext; this test only cares about localityCodes.
            return TaxContext()
        }
    }

    @org.springframework.boot.test.context.TestConfiguration
    class TestTaxConfig {
        @Bean
        @Primary
        fun capturingTaxClient(): CapturingTaxClient = CapturingTaxClient()
    }

    @Autowired
    lateinit var payrollRunService: PayrollRunService

    @Autowired
    lateinit var capturingTaxClient: CapturingTaxClient

    @AfterAll
    fun tearDown() {
        hrContext.close()
    }

    @Test
    fun `MI employee working in Detroit yields DETROIT locality code in TaxClient`() {
        val employerId = EmployerId("EMP-HR-MI-LOCAL")
        val payPeriodId = "2025-01-BW-MI"
        val employeeId = EmployeeId("EE-MI-DETROIT")

        val results = payrollRunService.runHrBackedPayForPeriod(
            employerId = employerId,
            payPeriodId = payPeriodId,
            employeeIds = listOf(employeeId),
        )

        assertEquals(1, results.size)

        // Verify that locality codes captured by TaxClient include DETROIT.
        assertTrue(capturingTaxClient.capturedLocalityCodes.isNotEmpty())
        val firstCallLocals = capturingTaxClient.capturedLocalityCodes.first()
        assertEquals(listOf("DETROIT"), firstCallLocals)
    }
}
