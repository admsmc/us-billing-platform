package com.example.usbilling.hr.tenancy

import com.example.usbilling.tenancy.db.TenantDataSources
import com.example.usbilling.tenancy.testsupport.DbPerEmployerTenancyTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
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

@SpringBootTest
@AutoConfigureMockMvc
class HrDbPerEmployerTenancyIT {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun tenancyProps(registry: DynamicPropertyRegistry) {
            DbPerEmployerTenancyTestSupport.registerH2Tenants(
                registry,
                tenantToDbName = mapOf(
                    "EMP1" to "hr_emp1",
                    "EMP2" to "hr_emp2",
                ),
            )
        }
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var tenants: TenantDataSources

    @Test
    fun `tenant routing prevents cross-tenant reads`() {
        val emp1 = JdbcTemplate(tenants.require("EMP1"))
        val emp2 = JdbcTemplate(tenants.require("EMP2"))

        // EMP1 has pay period, EMP2 does not.
        emp1.update("DELETE FROM pay_period")
        emp2.update("DELETE FROM pay_period")

        emp1.update(
            """
            INSERT INTO pay_period (
              employer_id, id, start_date, end_date, check_date,
              frequency, sequence_in_year
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            "EMP1",
            "PP-1",
            LocalDate.of(2025, 1, 1),
            LocalDate.of(2025, 1, 14),
            LocalDate.of(2025, 1, 15),
            "BIWEEKLY",
            1,
        )

        // EMP1 can read PP-1.
        mockMvc.get("/employers/EMP1/pay-periods/PP-1")
            .andExpect {
                status { isOk() }
                jsonPath("$.id") { value("PP-1") }
            }

        // EMP2 cannot read PP-1 because it is a different database.
        mockMvc.get("/employers/EMP2/pay-periods/PP-1")
            .andExpect {
                status { isOk() }
                content { string("") }
            }
    }

    @Test
    fun `header employer id must match path employer id`() {
        val result = mockMvc.get("/employers/EMP1/pay-periods/PP-1") {
            header("X-Employer-Id", "EMP2")
        }.andReturn()

        assertEquals(400, result.response.status)
    }
}
