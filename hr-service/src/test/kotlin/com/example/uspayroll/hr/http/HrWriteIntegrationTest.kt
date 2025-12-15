package com.example.uspayroll.hr.http

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import java.time.LocalDate
import kotlin.test.assertEquals

@SpringBootTest
@AutoConfigureMockMvc
class HrWriteIntegrationTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    private val employerId = "EMP-HR-WRITE"
    private val employeeId = "EE-WRITE-1"

    @BeforeEach
    fun cleanup() {
        jdbcTemplate.update("DELETE FROM employment_compensation")
        jdbcTemplate.update("DELETE FROM employee_profile_effective")
        jdbcTemplate.update("DELETE FROM employee")
        jdbcTemplate.update("DELETE FROM pay_period")
        jdbcTemplate.update("DELETE FROM pay_schedule")
        jdbcTemplate.update("DELETE FROM hr_audit_event")
        jdbcTemplate.update("DELETE FROM hr_idempotency_record")
    }

    @Test
    fun `create employee then effective-dated profile update yields correct snapshots and audit events`() {
        val effectiveFrom = LocalDate.of(2025, 1, 1)

        mockMvc.put("/employers/$employerId/employees/$employeeId") {
            header("Idempotency-Key", "ik-create-1")
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """
              {
                "profileEffectiveFrom": "$effectiveFrom",
                "homeState": "CA",
                "workState": "CA",
                "workCity": "San Francisco",
                "filingStatus": "SINGLE"
              }
            """.trimIndent()
        }.andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("CREATED") }
        }

        mockMvc.put("/employers/$employerId/employees/$employeeId/compensation") {
            header("Idempotency-Key", "ik-comp-1")
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """
              {
                "effectiveFrom": "$effectiveFrom",
                "compensationType": "SALARIED",
                "annualSalaryCents": 5200000,
                "payFrequency": "BIWEEKLY"
              }
            """.trimIndent()
        }.andExpect {
            status { isOk() }
        }

        val checkDate = LocalDate.of(2025, 1, 15)
        mockMvc.get("/employers/$employerId/employees/$employeeId/snapshot") {
            param("asOf", checkDate.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.workState") { value("CA") }
            jsonPath("$.baseCompensation.annualSalary.amount") { value(5200000) }
        }

        // Effective-dated update starting Feb 1.
        val effectiveFrom2 = LocalDate.of(2025, 2, 1)
        mockMvc.put("/employers/$employerId/employees/$employeeId/profile") {
            header("Idempotency-Key", "ik-prof-1")
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """
              {
                "effectiveFrom": "$effectiveFrom2",
                "workState": "NY",
                "workCity": "New York"
              }
            """.trimIndent()
        }.andExpect {
            status { isOk() }
        }

        // Snapshot before change.
        mockMvc.get("/employers/$employerId/employees/$employeeId/snapshot") {
            param("asOf", LocalDate.of(2025, 1, 20).toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.workState") { value("CA") }
        }

        // Snapshot after change.
        mockMvc.get("/employers/$employerId/employees/$employeeId/snapshot") {
            param("asOf", LocalDate.of(2025, 2, 15).toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.workState") { value("NY") }
            jsonPath("$.workCity") { value("New York") }
        }

        val auditCount = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM hr_audit_event WHERE employer_id = ?", Long::class.java, employerId)
        assertEquals(3L, auditCount, "Expected audit rows for create employee, compensation, and profile update")
    }
}
