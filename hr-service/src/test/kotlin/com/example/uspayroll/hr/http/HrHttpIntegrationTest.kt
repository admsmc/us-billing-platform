package com.example.uspayroll.hr.http

import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.LocalDate

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
        jdbcTemplate.update("DELETE FROM garnishment_order")
        jdbcTemplate.update("DELETE FROM employment_compensation")
        jdbcTemplate.update("DELETE FROM employee_profile_effective")
        jdbcTemplate.update("DELETE FROM employee")
        jdbcTemplate.update("DELETE FROM pay_period")
        jdbcTemplate.update("DELETE FROM pay_schedule")
        jdbcTemplate.update("DELETE FROM hr_audit_event")
        jdbcTemplate.update("DELETE FROM hr_idempotency_record")

        jdbcTemplate.update(
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
            ) VALUES (?, ?, 'CA', 'CA', 'San Francisco', 'SINGLE', 'REGULAR', ?, NULL, 0,
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

        // Snapshot reads are now based on effective-dated profiles.
        jdbcTemplate.update(
            """
            INSERT INTO employee_profile_effective (
              employer_id, employee_id,
              effective_from, effective_to,
              home_state, work_state, work_city,
              filing_status, employment_type,
              hire_date, termination_date,
              dependents,
              federal_withholding_exempt, is_nonresident_alien,
              w4_annual_credit_cents, w4_other_income_cents, w4_deductions_cents,
              w4_step2_multiple_jobs,
              w4_version, legacy_allowances, legacy_additional_withholding_cents, legacy_marital_status,
              w4_effective_date,
              additional_withholding_cents,
              fica_exempt, flsa_enterprise_covered, flsa_exempt_status, is_tipped_employee
            ) VALUES (?, ?, ?, ?, 'CA', 'CA', 'San Francisco', 'SINGLE', 'REGULAR', ?, NULL, 0,
                      FALSE, FALSE,
                      NULL, NULL, NULL,
                      FALSE,
                      NULL, NULL, NULL, NULL,
                      NULL,
                      NULL,
                      FALSE, TRUE, 'NON_EXEMPT', FALSE)
            """.trimIndent(),
            employerId.value,
            employeeId.value,
            LocalDate.of(2024, 6, 1),
            LocalDate.of(9999, 12, 31),
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

        // Seed a simple garnishment order that matches the generic
        // CREDITOR_GARNISHMENT rule in garnishment-rules.json. This exercises
        // the new order-backed path in GarnishmentHttpController.
        jdbcTemplate.update(
            """
            INSERT INTO garnishment_order (
              employer_id, employee_id, order_id,
              type, issuing_jurisdiction_type, issuing_jurisdiction_code,
              case_number, status,
              served_date, end_date,
              priority_class, sequence_within_class,
              initial_arrears_cents, current_arrears_cents
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            employerId.value,
            employeeId.value,
            "ORDER-HR-1",
            "CREDITOR_GARNISHMENT",
            "STATE",
            "CA",
            "CASE-123",
            "ACTIVE",
            LocalDate.of(2024, 12, 1),
            null,
            0,
            0,
            10_000_00L,
            9_000_00L,
        )
    }

    @Test
    fun `GET employee snapshot endpoint returns expected data`() {
        mockMvc.get("/employers/${employerId.value}/employees/${employeeId.value}/snapshot") {
            param("asOf", checkDate.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.employeeId") { value(employeeId.value) }
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

    @Test
    fun `GET garnishments endpoint returns order-backed data compatible with worker-service DTO`() {
        mockMvc.get("/employers/${employerId.value}/employees/${employeeId.value}/garnishments") {
            param("asOf", checkDate.toString())
        }.andExpect {
            status { isOk() }
            // Response is an array; validate first element fields that
            // worker-service's GarnishmentOrderDto expects, now backed by
            // garnishment_order plus rule config.
            jsonPath("$[0].orderId") { value("ORDER-HR-1") }
            jsonPath("$[0].planId") { value("GARN_PLAN_CREDITOR_GARNISHMENT") }
            jsonPath("$[0].type") { value("CREDITOR_GARNISHMENT") }
            jsonPath("$[0].caseNumber") { value("CASE-123") }
            jsonPath("$[0].formula.percent") { value(0.10) }
            jsonPath("$[0].arrearsBefore.amount") { value(900000) }
        }
    }

    @Test
    fun `GET garnishments endpoint applies employer-specific overrides and lesser-of formula`() {
        val overrideEmployerId = "EMP-GARN-OVERRIDE"
        mockMvc.get("/employers/$overrideEmployerId/employees/${employeeId.value}/garnishments") {
            param("asOf", checkDate.toString())
        }.andExpect {
            status { isOk() }
            // For EMP-GARN-OVERRIDE we expect only employer-scoped rules, not the
            // generic null-employer ones. The first rule should use the
            // LESSER_OF_PERCENT_OR_AMOUNT formula variant.
            jsonPath("$[0].type") { value("CREDITOR_GARNISHMENT") }
            jsonPath("$[0].formula.percent") { value(0.25) }
            jsonPath("$[0].formula.amount.amount") { value(150000) }
        }
    }

    @Test
    fun `GET garnishments endpoint returns NY employer-specific child support rule`() {
        val nyEmployerId = "EMP-GARN-NY"
        mockMvc.get("/employers/$nyEmployerId/employees/${employeeId.value}/garnishments") {
            param("asOf", checkDate.toString())
        }.andExpect {
            status { isOk() }
            // First rule: NY child support with protected floor.
            jsonPath("$[0].type") { value("CHILD_SUPPORT") }
            jsonPath("$[0].formula.percent") { value(0.50) }
            jsonPath("$[0].protectedEarningsRule.amount.amount") { value(250000) }
            // Second rule: NY creditor garnishment at 15%.
            jsonPath("$[1].type") { value("CREDITOR_GARNISHMENT") }
            jsonPath("$[1].formula.percent") { value(0.15) }
        }
    }

    @Test
    fun `GET garnishment ledger endpoint returns empty map before any withholdings`() {
        mockMvc.get("/employers/${employerId.value}/employees/${employeeId.value}/garnishments/ledger")
            .andExpect {
                status { isOk() }
                jsonPath("$") { isMap() }
            }
    }

    @Test
    fun `POST withholdings then GET ledger returns persisted entry`() {
        val body = """
            {
              "events": [
                {
                  "orderId": "ORDER-LEDGER-1",
                  "paycheckId": "CHK-LEDGER-1",
                  "payRunId": "RUN-LEDGER-1",
                  "checkDate": "$checkDate",
                  "withheld": { "amount": 12345, "currency": "USD" },
                  "netPay": { "amount": 500000, "currency": "USD" }
                }
              ]
            }
        """.trimIndent()

        mockMvc.post("/employers/${employerId.value}/employees/${employeeId.value}/garnishments/withholdings") {
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = body
        }.andExpect {
            status { isOk() }
        }

        mockMvc.get("/employers/${employerId.value}/employees/${employeeId.value}/garnishments/ledger")
            .andExpect {
                status { isOk() }
                jsonPath("$.['ORDER-LEDGER-1'].orderId") { value("ORDER-LEDGER-1") }
                jsonPath("$.['ORDER-LEDGER-1'].totalWithheld.amount") { value(12345) }
                jsonPath("$.['ORDER-LEDGER-1'].lastPaycheckId") { value("CHK-LEDGER-1") }
            }
    }

    @Test
    fun `arrears are reconciled from ledger into orders and completed orders drop from GET garnishments`() {
        val reconEmployerId = EmployerId("EMP-HR-RECON")
        val reconEmployeeId = EmployeeId("EE-HR-RECON-1")

        // Seed an order with an initial arrears balance and ACTIVE status.
        jdbcTemplate.update(
            """
            INSERT INTO garnishment_order (
              employer_id, employee_id, order_id,
              type, issuing_jurisdiction_type, issuing_jurisdiction_code,
              case_number, status,
              served_date, end_date,
              priority_class, sequence_within_class,
              initial_arrears_cents, current_arrears_cents
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            reconEmployerId.value,
            reconEmployeeId.value,
            "ORDER-RECON-1",
            "CREDITOR_GARNISHMENT",
            "STATE",
            "CA",
            "CASE-RECON-1",
            "ACTIVE",
            LocalDate.of(2024, 12, 1),
            null,
            0,
            0,
            10_000_00L,
            10_000_00L,
        )

        // First withholding of 4,000.00 reduces remaining arrears to 6,000.00
        val firstBody = """
            {
              "events": [
                {
                  "orderId": "ORDER-RECON-1",
                  "paycheckId": "CHK-RECON-1",
                  "payRunId": "RUN-RECON-1",
                  "checkDate": "$checkDate",
                  "withheld": { "amount": 400000, "currency": "USD" },
                  "netPay": { "amount": 1000000, "currency": "USD" }
                }
              ]
            }
        """.trimIndent()

        mockMvc.post("/employers/${reconEmployerId.value}/employees/${reconEmployeeId.value}/garnishments/withholdings") {
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = firstBody
        }.andExpect {
            status { isOk() }
        }

        // Order should still be ACTIVE and appear in GET /garnishments with
        // arrearsBefore reflecting the updated remaining balance of 6,000.00.
        mockMvc.get("/employers/${reconEmployerId.value}/employees/${reconEmployeeId.value}/garnishments") {
            param("asOf", checkDate.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$[0].orderId") { value("ORDER-RECON-1") }
            jsonPath("$[0].arrearsBefore.amount") { value(600000) }
        }

        // Second withholding of 6,000.00 pays the order in full.
        val secondBody = """
            {
              "events": [
                {
                  "orderId": "ORDER-RECON-1",
                  "paycheckId": "CHK-RECON-2",
                  "payRunId": "RUN-RECON-2",
                  "checkDate": "$checkDate",
                  "withheld": { "amount": 600000, "currency": "USD" },
                  "netPay": { "amount": 1000000, "currency": "USD" }
                }
              ]
            }
        """.trimIndent()

        mockMvc.post("/employers/${reconEmployerId.value}/employees/${reconEmployeeId.value}/garnishments/withholdings") {
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = secondBody
        }.andExpect {
            status { isOk() }
        }

        // After reconciliation, the order should be marked COMPLETED and
        // excluded from the active /garnishments view.
        mockMvc.get("/employers/${reconEmployerId.value}/employees/${reconEmployeeId.value}/garnishments") {
            param("asOf", checkDate.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$") { isEmpty() }
        }
    }
}
