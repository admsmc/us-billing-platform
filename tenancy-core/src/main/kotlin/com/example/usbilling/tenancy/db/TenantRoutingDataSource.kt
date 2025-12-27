package com.example.usbilling.tenancy.db

import com.example.usbilling.tenancy.TenantContext
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource

/**
 * Routes DB connections based on [TenantContext].
 *
 * IMPORTANT: there is intentionally no default datasource.
 */
class TenantRoutingDataSource : AbstractRoutingDataSource() {
    override fun determineCurrentLookupKey(): Any = TenantContext.require()
}
