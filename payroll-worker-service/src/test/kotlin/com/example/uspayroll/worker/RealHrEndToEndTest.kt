package com.example.uspayroll.worker

import com.example.uspayroll.hr.HrApplication
import com.example.uspayroll.payroll.model.*
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.worker.support.StubTaxLaborClientsTestConfig
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
import org.springframework.context.annotation.Import
import org.springframework.test.context.ContextConfiguration
import java.sql.Connection
import java.time.LocalDate
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(StubTaxLaborClientsTestConfig::class)
@ContextConfiguration(initializers = [RealHrEndToEndTest.Companion.Initializer::class])
@TestInstance(Lifecycle.PER_CLASS)
class RealHrEndToEndTest {

    companion object {
        lateinit var hrContext: ConfigurableApplicationContext

        class Initializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
            override fun initialize(context: ConfigurableApplicationContext) {
                // Start real hr-service on an ephemeral port with H2.
                // NOTE: we use command-line args (highest precedence) to avoid
                // other services' application.yml defaults on the test classpath
                // overriding these properties.
                hrContext = SpringApplicationBuilder(HrApplication::class.java)
                    .run(
                        "--server.port=0",
                        "--spring.datasource.url=jdbc:h2:mem:hr_e2e",
                        "--spring.datasource.driverClassName=org.h2.Driver",
                        "--spring.datasource.username=sa",
                        "--spring.datasource.password=",
                        "--spring.flyway.enabled=true",
                        "--spring.flyway.locations=classpath:db/migration/hr",
                    )

                // Seed HR data in the real HR database using plain JDBC.
                val dataSource = hrContext.getBean(DataSource::class.java)
                fun Connection.exec(sql: String, vararg args: Any?) {
                    prepareStatement(sql).use { ps ->
                        args.forEachIndexed { index, arg ->
                            ps.setObject(index + 1, arg)
                        }
                        ps.executeUpdate()
                    }
                }
                val employerId = "EMP-HR-E2E"
                val employeeId = "EE-E2E-1"
                val payPeriodId = "2025-01-BW1"
                val checkDate = LocalDate.of(2025, 1, 15)

                dataSource.connection.use { conn ->
                    conn.exec("DELETE FROM employment_compensation")
                    conn.exec("DELETE FROM employee_profile_effective")
                    conn.exec("DELETE FROM employee")
                    conn.exec("DELETE FROM pay_period")

                    val hireDate = LocalDate.of(2024, 6, 1)

                    conn.exec(
                        """
                    INSERT INTO employee (
                      employer_id, employee_id, home_state, work_state,
                      filing_status, employment_type,
                      hire_date, termination_date,
                      dependents,
                      federal_withholding_exempt, is_nonresident_alien,
                      w4_annual_credit_cents, w4_other_income_cents, w4_deductions_cents,
                      w4_step2_multiple_jobs,
                      additional_withholding_cents,
                      fica_exempt, flsa_enterprise_covered, flsa_exempt_status, is_tipped_employee
                    ) VALUES (?, ?, 'CA', 'CA', 'SINGLE', 'REGULAR', ?, NULL, 0,
                              FALSE, FALSE,
                              NULL, NULL, NULL,
                              FALSE,
                              NULL,
                              FALSE, TRUE, 'NON_EXEMPT', FALSE)
                        """.trimIndent(),
                        employerId,
                        employeeId,
                        hireDate,
                    )

                    conn.exec(
                        """
                        INSERT INTO employee_profile_effective (
                          employer_id, employee_id,
                          effective_from, effective_to,
                          home_state, work_state, work_city,
                          filing_status, employment_type,
                          hire_date, termination_date,
                          dependents,
                          federal_withholding_exempt, is_nonresident_alien,
                          w4_step2_multiple_jobs,
                          additional_withholding_cents,
                          fica_exempt, flsa_enterprise_covered, flsa_exempt_status, is_tipped_employee
                        ) VALUES (?, ?, ?, ?, 'CA', 'CA', NULL, 'SINGLE', 'REGULAR', ?, NULL, 0,
                                  FALSE, FALSE,
                                  FALSE,
                                  NULL,
                                  FALSE, TRUE, 'NON_EXEMPT', FALSE)
                        """.trimIndent(),
                        employerId,
                        employeeId,
                        hireDate,
                        LocalDate.of(9999, 12, 31),
                        hireDate,
                    )

                    conn.exec(
                        """
                    INSERT INTO employment_compensation (
                      employer_id, employee_id,
                      effective_from, effective_to,
                      compensation_type,
                      annual_salary_cents, hourly_rate_cents, pay_frequency
                    ) VALUES (?, ?, ?, ?, 'SALARIED', 52_00000, NULL, 'BIWEEKLY')
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

                val hrPort = requireNotNull(hrContext.environment.getProperty("local.server.port")) {
                    "Expected hr-service to expose local.server.port"
                }

                // Disable garnishment application for this E2E so that HR's
                // demo rule synthesis doesn't zero out net pay.
                TestPropertyValues.of(
                    "hr.base-url=http://localhost:$hrPort",
                    "worker.garnishments.enabled-employers=EMP-NOT-HR-E2E",
                ).applyTo(context.environment)
            }
        }
    }

    @Autowired
    lateinit var payrollRunService: PayrollRunService

    @AfterAll
    fun tearDown() {
        hrContext.close()
    }

    @Test
    fun `worker-service computes paycheck using real hr-service over HTTP`() {
        val employerId = EmployerId("EMP-HR-E2E")
        val payPeriodId = "2025-01-BW1"
        val employeeId = EmployeeId("EE-E2E-1")

        val results = payrollRunService.runHrBackedPayForPeriod(
            employerId = employerId,
            payPeriodId = payPeriodId,
            employeeIds = listOf(employeeId),
        )

        assertEquals(1, results.size)
        val paycheck = results.first()

        assertEquals(employeeId, paycheck.employeeId)
        assertTrue(paycheck.gross.amount > 0L)
        assertTrue(paycheck.net.amount > 0L)
    }
}
