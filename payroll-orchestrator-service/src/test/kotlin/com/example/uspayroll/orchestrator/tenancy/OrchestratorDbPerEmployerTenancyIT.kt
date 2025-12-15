package com.example.uspayroll.orchestrator.tenancy

import com.example.uspayroll.orchestrator.support.StubClientsTestConfig
import com.example.uspayroll.tenancy.db.TenantDataSources
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertEquals

@SpringBootTest(
    properties = [
        "tenancy.mode=DB_PER_EMPLOYER",
        "tenancy.databases.EMP1.url=jdbc:h2:mem:orch_emp1;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "tenancy.databases.EMP1.username=sa",
        "tenancy.databases.EMP1.password=",
        "tenancy.databases.EMP2.url=jdbc:h2:mem:orch_emp2;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "tenancy.databases.EMP2.username=sa",
        "tenancy.databases.EMP2.password=",
        "orchestrator.internal-auth.shared-secret=dev-internal-token",
        "server.port=0",
    ],
)
@AutoConfigureMockMvc
@Import(StubClientsTestConfig::class)
class OrchestratorDbPerEmployerTenancyIT {

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
