package com.example.usbilling.tenancy.db

import com.example.usbilling.persistence.flyway.FlywaySupport
import org.slf4j.LoggerFactory

object TenantFlywayMigrator {

    private val logger = LoggerFactory.getLogger(TenantFlywayMigrator::class.java)

    fun migrateAll(dataSources: TenantDataSources, vararg locations: String) {
        dataSources.byTenant.forEach { (tenant, ds) ->
            logger.info("flyway.migrate.tenant tenant={} locations={}", tenant, locations.joinToString(","))
            FlywaySupport.migrate(ds, *locations)
        }
    }
}
