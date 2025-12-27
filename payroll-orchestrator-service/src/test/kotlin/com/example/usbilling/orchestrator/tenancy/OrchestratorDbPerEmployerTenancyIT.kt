package com.example.usbilling.orchestrator.tenancy

import com.example.usbilling.orchestrator.support.StubClientsTestConfig
import com.example.usbilling.tenancy.db.TenantDataSources
import com.example.usbilling.tenancy.testsupport.DbPerEmployerTenancyTestSupport
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertEquals

@SpringBootTest
@AutoConfigureMockMvc
@Import(StubClientsTestConfig::class)
class OrchestratorDbPerEmployerTenancyIT {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun tenancyProps(registry: DynamicPropertyRegistry) {
            DbPerEmployerTenancyTestSupport.registerH2Tenants(
                registry,
                tenantToDbName = mapOf(
                    "EMP1" to "orch_emp1",
                    "EMP2" to "orch_emp2",
                ),
            )
            registry.add("orchestrator.internal-auth.jwt-keys.k1") { "dev-internal-token" }
            registry.add("orchestrator.internal-auth.jwt-default-kid") { "k1" }
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

        emp1.update("DELETE FROM pay_run_item")
        emp1.update("DELETE FROM pay_run")
        emp2.update("DELETE FROM pay_run_item")
        emp2.update("DELETE FROM pay_run")
    }

    @Test
    fun `tenant routing prevents cross-tenant reads`() {
        emp1.update(
            """
            INSERT INTO pay_run (employer_id, pay_run_id, pay_period_id, status)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            "EMP1",
            "RUN-1",
            "PP-1",
            "RUNNING",
        )

        mockMvc.get("/employers/EMP1/payruns/RUN-1")
            .andExpect {
                status { isOk() }
                jsonPath("$.payRunId") { value("RUN-1") }
            }

        mockMvc.get("/employers/EMP2/payruns/RUN-1")
            .andExpect {
                status { isNotFound() }
            }
    }

    @Test
    fun `header employer id must match path employer id`() {
        val result = mockMvc.get("/employers/EMP1/payruns/RUN-1") {
            header("X-Employer-Id", "EMP2")
        }.andReturn()

        assertEquals(400, result.response.status)
    }
}
