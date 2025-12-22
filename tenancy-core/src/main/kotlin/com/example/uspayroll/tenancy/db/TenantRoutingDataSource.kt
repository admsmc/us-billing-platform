package com.example.uspayroll.tenancy.db

import com.example.uspayroll.tenancy.TenantContext
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource

/**
 * Routes DB connections based on [TenantContext].
 *
 * IMPORTANT: there is intentionally no default datasource.
 */
class TenantRoutingDataSource : AbstractRoutingDataSource() {
    override fun determineCurrentLookupKey(): Any = TenantContext.require()
}
