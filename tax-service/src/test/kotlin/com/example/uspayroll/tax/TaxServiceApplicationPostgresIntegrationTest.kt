package com.example.uspayroll.tax

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertTrue

/**
 * Verifies that the Spring Boot tax-service application can start against a
 * real Postgres instance and that Flyway successfully applies the tax_rule
 * schema migration.
 */
@Testcontainers
@SpringBootTest
class TaxServiceApplicationPostgresIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine").apply {
            withDatabaseName("us_payroll_tax")
            withUsername("tax_service")
            withPassword("changeme")
        }

        @JvmStatic
        @DynamicPropertySource
        fun registerTaxDbProperties(registry: DynamicPropertyRegistry) {
            registry.add("tax.db.url") { postgres.jdbcUrl }
            registry.add("tax.db.username") { postgres.username }
            registry.add("tax.db.password") { postgres.password }
        }
    }

    @Autowired
    lateinit var dsl: DSLContext

    @Test
    fun `Boot context loads and tax_rule table exists in Postgres`() {
        // If Flyway ran successfully, the tax_rule table should exist and be
        // queryable. We don't require any rows, just that the table is present.
        val count = dsl.fetchCount(DSL.table("tax_rule"))
        assertTrue(count >= 0)
    }
}
