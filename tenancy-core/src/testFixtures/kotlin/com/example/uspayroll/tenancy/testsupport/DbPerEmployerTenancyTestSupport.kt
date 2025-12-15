package com.example.uspayroll.tenancy.testsupport

import org.springframework.test.context.DynamicPropertyRegistry

object DbPerEmployerTenancyTestSupport {

    fun registerH2Tenants(registry: DynamicPropertyRegistry, tenantToDbName: Map<String, String>, includeServerPort0: Boolean = true) {
        registry.add("tenancy.mode") { "DB_PER_EMPLOYER" }

        tenantToDbName.forEach { (tenant, dbName) ->
            val prefix = "tenancy.databases.$tenant"
            registry.add("$prefix.url") { h2MemUrl(dbName) }
            registry.add("$prefix.username") { "sa" }
            registry.add("$prefix.password") { "" }
        }

        if (includeServerPort0) {
            registry.add("server.port") { "0" }
        }
    }

    private fun h2MemUrl(dbName: String): String {
        // MODE=PostgreSQL keeps schema DDL compatible with the production DB dialect.
        // DB_CLOSE_DELAY=-1 keeps the in-memory DB around for the duration of the JVM.
        return "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
    }
}
