package com.example.uspayroll.persistence.flyway

import org.flywaydb.core.Flyway
import javax.sql.DataSource

object FlywaySupport {

    /** Apply migrations without allowing `clean()` (safe default). */
    fun migrate(dataSource: DataSource, vararg locations: String) {
        Flyway.configure()
            .dataSource(dataSource)
            .locations(*locations)
            .load()
            .migrate()
    }

    /**
     * Clean + migrate (intended for ephemeral test databases only).
     *
     * Note: enables clean explicitly.
     */
    fun cleanAndMigrate(dataSource: DataSource, vararg locations: String) {
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations(*locations)
            .cleanDisabled(false)
            .load()

        flyway.clean()
        flyway.migrate()
    }
}
