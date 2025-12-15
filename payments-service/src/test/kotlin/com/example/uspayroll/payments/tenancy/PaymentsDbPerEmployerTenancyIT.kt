package com.example.uspayroll.payments.tenancy

import com.example.uspayroll.tenancy.db.TenantDataSources
import com.example.uspayroll.tenancy.testsupport.DbPerEmployerTenancyTestSupport
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
import kotlin.test.assertEquals

@SpringBootTest
@AutoConfigureMockMvc
class PaymentsDbPerEmployerTenancyIT {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun tenancyProps(registry: DynamicPropertyRegistry) {
            DbPerEmployerTenancyTestSupport.registerH2Tenants(
                registry,
                tenantToDbName = mapOf(
                    "EMP1" to "payments_emp1",
                    "EMP2" to "payments_emp2",
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

        emp1.update("DELETE FROM paycheck_payment")
        emp2.update("DELETE FROM paycheck_payment")
    }

    @Test
    fun `tenant routing prevents cross-tenant reads`() {
        emp1.update(
            """
            INSERT INTO paycheck_payment (
              employer_id, payment_id, paycheck_id,
              pay_run_id, employee_id, pay_period_id,
              net_cents
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            "EMP1",
            "PAY-1",
            "CHK-1",
            "RUN-1",
            "EE-1",
            "PP-1",
            12345L,
        )

        mockMvc.get("/employers/EMP1/payments/by-paycheck/CHK-1")
            .andExpect {
                status { isOk() }
                jsonPath("$.paymentId") { value("PAY-1") }
                jsonPath("$.netCents") { value(12345) }
            }

        // Different tenant DB -> not found.
        mockMvc.get("/employers/EMP2/payments/by-paycheck/CHK-1")
            .andExpect {
                status { isNotFound() }
            }
    }

    @Test
    fun `header employer id must match path employer id`() {
        val result = mockMvc.get("/employers/EMP1/payments/by-paycheck/CHK-1") {
            header("X-Employer-Id", "EMP2")
        }.andReturn()

        assertEquals(400, result.response.status)
    }
}
