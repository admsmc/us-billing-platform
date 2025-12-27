package com.example.usbilling.tenancy.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource

data class TenantDbConfig(
    val url: String,
    val username: String,
    val password: String,
    val driverClassName: String? = null,
)

data class TenantDataSources(
    val byTenant: Map<String, DataSource>,
) {
    init {
        require(byTenant.isNotEmpty()) { "TenantDataSources cannot be empty" }
    }

    fun require(tenant: String): DataSource = requireNotNull(byTenant[tenant]) {
        "No DataSource configured for tenant '$tenant'"
    }
}

object TenantDataSourceFactory {

    fun buildHikari(tenant: String, cfg: TenantDbConfig): DataSource {
        val hc = HikariConfig().apply {
            jdbcUrl = cfg.url
            username = cfg.username
            password = cfg.password
            poolName = "tenant-$tenant"
            maximumPoolSize = 5
            minimumIdle = 0
            isAutoCommit = true
            cfg.driverClassName?.let { driverClassName = it }
        }
        return HikariDataSource(hc)
    }

    fun routing(byTenant: Map<String, DataSource>): DataSource {
        val routing = TenantRoutingDataSource()

        // AbstractRoutingDataSource is Java-typed to Map<Object,Object>.
        @Suppress("UNCHECKED_CAST")
        val target: MutableMap<Any, Any> = byTenant
            .mapValues { (_, ds) -> ds as Any }
            .toMutableMap() as MutableMap<Any, Any>

        routing.setTargetDataSources(target)
        routing.afterPropertiesSet()
        return routing
    }
}
