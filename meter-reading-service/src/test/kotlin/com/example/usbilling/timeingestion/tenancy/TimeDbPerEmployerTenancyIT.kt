package com.example.usbilling.timeingestion.tenancy

import com.example.usbilling.tenancy.db.TenantDataSources
import com.example.usbilling.tenancy.testsupport.DbPerEmployerTenancyTestSupport
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.LocalDate
import kotlin.test.assertEquals

@SpringBootTest
@AutoConfigureMockMvc
class TimeDbPerEmployerTenancyIT {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun tenancyProps(registry: DynamicPropertyRegistry) {
            DbPerEmployerTenancyTestSupport.registerH2Tenants(
                registry,
                tenantToDbName = mapOf(
                    "EMP1" to "time_emp1",
                    "EMP2" to "time_emp2",
                ),
            )
        }
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var tenants: TenantDataSources

    private lateinit var emp1: JdbcTemplate
    private lateinit var emp2: JdbcTemplate

    @BeforeEach
    fun cleanup() {
        emp1 = JdbcTemplate(tenants.require("EMP1"))
        emp2 = JdbcTemplate(tenants.require("EMP2"))

        emp1.update("DELETE FROM time_entry")
        emp2.update("DELETE FROM time_entry")
    }

    @Test
    fun `tenant routing prevents cross-tenant reads even when employer_id column matches path`() {
        // Insert a row into EMP1's DB but label it as employer_id=EMP2.
        // If routing is broken (always hitting EMP1), EMP2 requests could see this row.
        val workDate = LocalDate.of(2025, 1, 6)

        emp1.update(
            """
            INSERT INTO time_entry (employer_id, employee_id, entry_id, work_date, hours)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            "EMP2",
            "EE-1",
            "E-1",
            workDate,
            10.0,
        )

        mockMvc.get(
            "/employers/EMP2/employees/EE-1/time-summary?start=$workDate&end=$workDate&workState=CA&weekStartsOn=MONDAY",
        ).andExpect {
            status { isOk() }
            jsonPath("$.totals.regularHours") { value(0.0) }
            jsonPath("$.totals.overtimeHours") { value(0.0) }
            jsonPath("$.totals.doubleTimeHours") { value(0.0) }
        }
    }

    @Test
    fun `header employer id must match path employer id`() {
        val workDate = LocalDate.of(2025, 1, 6)

        val result = mockMvc.get(
            "/employers/EMP1/employees/EE-1/time-summary?start=$workDate&end=$workDate&workState=CA&weekStartsOn=MONDAY",
        ) {
            header("X-Employer-Id", "EMP2")
        }.andReturn()

        assertEquals(400, result.response.status)
    }
}
