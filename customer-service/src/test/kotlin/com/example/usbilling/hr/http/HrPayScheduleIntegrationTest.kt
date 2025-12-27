package com.example.usbilling.hr.http

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.LocalDate
import kotlin.test.assertEquals

@SpringBootTest
@AutoConfigureMockMvc
class HrPayScheduleIntegrationTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    private val employerId = "EMP-HR-SCHEDULE"
    private val scheduleId = "BW"

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
    fun `upsert schedule then generate pay periods for year is deterministic and exposes by-check-date lookup`() {
        // Anchor one period before 2025 so the first 2025 check date is in mid-January.
        val firstStartDate = LocalDate.of(2024, 12, 18)

        mockMvc.put("/employers/$employerId/pay-schedules/$scheduleId") {
            header("Idempotency-Key", "ik-sched-1")
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """
              {
                "frequency": "BIWEEKLY",
                "firstStartDate": "$firstStartDate",
                "checkDateOffsetDays": 0
              }
            """.trimIndent()
        }.andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("CREATED") }
        }

        mockMvc.post("/employers/$employerId/pay-schedules/$scheduleId/generate-pay-periods") {
            header("Idempotency-Key", "ik-generate-1")
            param("year", "2025")
        }.andExpect {
            status { isCreated() }
            jsonPath("$.generatedCount") { value(26) }
            jsonPath("$.createdCount") { value(26) }
        }

        // Run again with the same idempotency key; should return the same stored response.
        mockMvc.post("/employers/$employerId/pay-schedules/$scheduleId/generate-pay-periods") {
            header("Idempotency-Key", "ik-generate-1")
            param("year", "2025")
        }.andExpect {
            status { isCreated() }
            jsonPath("$.generatedCount") { value(26) }
            jsonPath("$.createdCount") { value(26) }
        }

        val payPeriodCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(1) FROM pay_period WHERE employer_id = ?",
            Long::class.java,
            employerId,
        )
        assertEquals(26L, payPeriodCount)

        // Verify by-check-date endpoint resolves a period.
        val firstCheckDate = LocalDate.of(2025, 1, 14)
        mockMvc.get("/employers/$employerId/pay-periods/by-check-date") {
            param("checkDate", firstCheckDate.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.checkDate") { value(firstCheckDate.toString()) }
            jsonPath("$.frequency") { value("BIWEEKLY") }
            jsonPath("$.sequenceInYear") { value(1) }
        }

        // Overlap prevention: attempt to insert a pay period that overlaps the first generated period.
        mockMvc.put("/employers/$employerId/pay-periods/OVERLAP-1") {
            header("Idempotency-Key", "ik-overlap-1")
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """
              {
                "startDate": "2025-01-01",
                "endDate": "2025-01-10",
                "checkDate": "2025-01-10",
                "frequency": "BIWEEKLY",
                "sequenceInYear": 999
              }
            """.trimIndent()
        }.andExpect {
            status { isConflict() }
            jsonPath("$.error") { value("PAY_PERIOD_OVERLAP") }
        }
    }
}
