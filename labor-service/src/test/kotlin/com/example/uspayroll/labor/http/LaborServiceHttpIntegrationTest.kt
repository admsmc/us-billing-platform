package com.example.uspayroll.labor.http

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * End-to-end HTTP integration tests for labor-service.
 *
 * These tests run against an in-memory H2 database in PostgreSQL mode with
 * Flyway migrations applied on startup, then insert a small set of
 * labor_standard rows and hit the HTTP endpoint.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "labor.db.url=jdbc:h2:mem:labor_http;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "labor.db.username=sa",
        "labor.db.password=",
    ],
)
@AutoConfigureMockMvc
class LaborServiceHttpIntegrationTest {

    @Autowired
    lateinit var dsl: DSLContext

    @Autowired
    lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setupData() {
        dsl.deleteFrom(DSL.table("labor_standard")).execute()

        fun insertLaborStandard(
            state: String,
            regularMinCents: Long,
            tippedMinCents: Long? = null,
        ) {
            dsl.insertInto(DSL.table("labor_standard"))
                .columns(
                    DSL.field("state_code"),
                    DSL.field("effective_from"),
                    DSL.field("effective_to"),
                    DSL.field("regular_minimum_wage_cents"),
                    DSL.field("tipped_minimum_cash_wage_cents"),
                )
                .values(
                    state,
                    java.sql.Date.valueOf("2025-01-01"),
                    null,
                    regularMinCents,
                    tippedMinCents,
                )
                .execute()
        }

        insertLaborStandard("CA", 1_650L, null)
        insertLaborStandard("TX", 725L, 213L)
    }

    @Test
    fun `GET labor-standards returns DTO backed by DB rows`() {
        val employerId = "EMP-LABOR-HTTP-INT"
        val asOf = "2025-06-30"

        // CA: high minimum, no tipped cash minimum.
        mockMvc.get("/employers/$employerId/labor-standards") {
            param("asOf", asOf)
            param("state", "CA")
        }.andExpect {
            status { isOk() }
            jsonPath("$.federalMinimumWageCents") { value(1_650) }
            jsonPath("$.federalTippedCashMinimumCents") { doesNotExist() }
        }

        // TX: baseline minimum with tipped cash minimum.
        mockMvc.get("/employers/$employerId/labor-standards") {
            param("asOf", asOf)
            param("state", "TX")
        }.andExpect {
            status { isOk() }
            jsonPath("$.federalMinimumWageCents") { value(725) }
            jsonPath("$.federalTippedCashMinimumCents") { value(213) }
        }
    }

    @Test
    fun `NY statewide vs NYC locality uses different minimum wages from DB`() {
        val employerId = "EMP-LABOR-HTTP-LOCAL-NY"
        val asOf = "2025-06-30"

        // Seed two NY rows: statewide 15.50 and NYC 16.50.
        dsl.deleteFrom(DSL.table("labor_standard"))
            .where(DSL.field("state_code").eq("NY"))
            .execute()

        fun insertNy(
            localityCode: String?,
            regularMinCents: Long,
        ) {
            dsl.insertInto(DSL.table("labor_standard"))
                .columns(
                    DSL.field("state_code"),
                    DSL.field("locality_code"),
                    DSL.field("effective_from"),
                    DSL.field("effective_to"),
                    DSL.field("regular_minimum_wage_cents"),
                )
                .values(
                    "NY",
                    localityCode,
                    java.sql.Date.valueOf("2025-01-01"),
                    null,
                    regularMinCents,
                )
                .execute()
        }

        insertNy(localityCode = null, regularMinCents = 1_550L)
        insertNy(localityCode = "NYC", regularMinCents = 1_650L)

        // 1) Statewide NY (no locality) should use baseline 15.50.
        mockMvc.get("/employers/$employerId/labor-standards") {
            param("asOf", asOf)
            param("state", "NY")
        }.andExpect {
            status { isOk() }
            jsonPath("$.federalMinimumWageCents") { value(1_550) }
        }

        // 2) NYC locality should use local 16.50 row.
        mockMvc.get("/employers/$employerId/labor-standards") {
            param("asOf", asOf)
            param("state", "NY")
            param("locality", "NYC")
        }.andExpect {
            status { isOk() }
            jsonPath("$.federalMinimumWageCents") { value(1_650) }
        }
    }
}
