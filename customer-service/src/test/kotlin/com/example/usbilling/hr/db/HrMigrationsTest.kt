package com.example.usbilling.hr.db

import com.example.usbilling.persistence.flyway.FlywaySupport
import org.h2.jdbcx.JdbcDataSource
import kotlin.test.Test

/**
 * Sanity check that HR Flyway migrations apply cleanly against an in-memory H2
 * database. This guards basic schema validity without wiring a full Spring
 * Boot application yet.
 */
class HrMigrationsTest {

    @Test
    fun `hr schema migrations apply successfully against H2`() {
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:hr_migrations;DB_CLOSE_DELAY=-1")
            user = "sa"
            password = ""
        }

        FlywaySupport.migrate(
            dataSource = dataSource,
            "classpath:db/migration",
        )
    }
}
