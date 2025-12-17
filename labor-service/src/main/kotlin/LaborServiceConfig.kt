package com.example.uspayroll.labor.config

import com.example.uspayroll.labor.api.LaborStandardsCatalog
import com.example.uspayroll.labor.api.LaborStandardsContextProvider
import com.example.uspayroll.labor.impl.CatalogBackedLaborStandardsContextProvider
import com.example.uspayroll.labor.persistence.JooqLaborStandardsCatalog
import com.example.uspayroll.tenancy.db.TenantDataSourceFactory
import com.example.uspayroll.tenancy.db.TenantDbConfig
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

@Configuration
class LaborServiceConfig {

    @Bean
    @ConditionalOnProperty(prefix = "tenancy", name = ["mode"], havingValue = "SINGLE", matchIfMissing = true)
    fun laborDataSource(@Value("\${labor.db.url}") url: String, @Value("\${labor.db.username}") username: String, @Value("\${labor.db.password}") password: String): DataSource {
        val driverClassName = when {
            url.startsWith("jdbc:h2:") -> "org.h2.Driver"
            url.startsWith("jdbc:postgresql:") -> "org.postgresql.Driver"
            else -> null
        }

        // IMPORTANT: Use a pool (Hikari) rather than DriverManagerDataSource.
        // DriverManagerDataSource opens a new physical connection per operation and defeats
        // driver-level caches, which can create heavy pg_catalog metadata traffic.
        return TenantDataSourceFactory.buildHikari(
            tenant = "labor-single",
            cfg = TenantDbConfig(
                url = url,
                username = username,
                password = password,
                driverClassName = driverClassName,
            ),
        )
    }

    @Bean
    @ConditionalOnProperty(prefix = "tenancy", name = ["mode"], havingValue = "SINGLE", matchIfMissing = true)
    fun laborDslContext(dataSource: DataSource, @Value("\${labor.db.url}") url: String): DSLContext {
        val dialect = if (url.startsWith("jdbc:h2:")) {
            org.jooq.SQLDialect.H2
        } else {
            org.jooq.SQLDialect.POSTGRES
        }

        return DSL.using(dataSource, dialect)
    }

    @Bean
    fun laborStandardsCatalog(dsl: DSLContext): LaborStandardsCatalog {
        // Swap between JooqLaborStandardsCatalog and InMemoryLaborStandardsCatalog
        // depending on environment. For now, we wrap the Jooq version.
        return JooqLaborStandardsCatalog(dsl)
    }

    @Bean
    fun laborStandardsContextProvider(catalog: LaborStandardsCatalog): LaborStandardsContextProvider = CatalogBackedLaborStandardsContextProvider(catalog)
}
