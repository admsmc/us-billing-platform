package com.example.uspayroll.hr.http

import com.example.uspayroll.payroll.model.EmployeeSnapshot
import com.example.uspayroll.payroll.model.PayPeriod
import com.example.uspayroll.payroll.model.LocalDateRange
import com.example.uspayroll.payroll.model.PayFrequency
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.LocalDate
import kotlin.test.assertEquals

@SpringBootTest
@AutoConfigureMockMvc
class HrHttpIntegrationTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    private val employerId = EmployerId("EMP-HR-HTTP-INT")
    private val employeeId = EmployeeId("EE-HTTP-1")
    private val payPeriodId = "2025-01-BW1"
    private val checkDate = LocalDate.of(2025, 1, 15)

    @BeforeEach
    fun seedData() {
        jdbcTemplate.update("DELETE FROM employment_compensation")
        jdbcTemplate.update("DELETE FROM employee")
        jdbcTemplate.update("DELETE FROM pay_period")

        jdbcTemplate.update(
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
            employerId.value,
            employeeId.value,
            LocalDate.of(2024, 6, 1),
        )

        jdbcTemplate.update(
            """
            INSERT INTO employment_compensation (
              employer_id, employee_id,
              effective_from, effective_to,
              compensation_type,
              annual_salary_cents, hourly_rate_cents, pay_frequency
            ) VALUES (?, ?, ?, ?, 'SALARIED', 52_00000, NULL, 'BIWEEKLY')
            """.trimIndent(),
            employerId.value,
            employeeId.value,
            LocalDate.of(2024, 6, 1),
            LocalDate.of(9999, 12, 31),
        )

        jdbcTemplate.update(
            """
            INSERT INTO pay_period (
              employer_id, id,
              start_date, end_date, check_date,
              frequency, sequence_in_year
            ) VALUES (?, ?, ?, ?, ?, 'BIWEEKLY', 1)
            """.trimIndent(),
            employerId.value,
            payPeriodId,
            LocalDate.of(2025, 1, 1),
            LocalDate.of(2025, 1, 14),
            checkDate,
        )
    }

    @Test
    fun `GET employee snapshot endpoint returns expected data`() {
        mockMvc.get("/employers/${employerId.value}/employees/${employeeId.value}/snapshot") {
            param("asOf", checkDate.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.employeeId.value") { value(employeeId.value) }
            jsonPath("$.homeState") { value("CA") }
            jsonPath("$.baseCompensation.annualSalary.amount") { value(52_000_00) }
        }
    }

    @Test
    fun `GET pay period endpoint returns expected data`() {
        mockMvc.get("/employers/${employerId.value}/pay-periods/$payPeriodId")
            .andExpect {
                status { isOk() }
                jsonPath("$.id") { value(payPeriodId) }
                jsonPath("$.checkDate") { value(checkDate.toString()) }
                jsonPath("$.frequency") { value("BIWEEKLY") }
            }
    }
}