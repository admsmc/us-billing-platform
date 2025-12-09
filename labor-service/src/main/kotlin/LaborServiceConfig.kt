package com.example.uspayroll.labor.config

import com.example.uspayroll.labor.api.LaborStandardsCatalog
import com.example.uspayroll.labor.impl.CatalogBackedLaborStandardsContextProvider
import com.example.uspayroll.labor.impl.InMemoryLaborStandardsCatalog
import com.example.uspayroll.labor.impl.LaborStandardsContextProvider
import com.example.uspayroll.labor.persistence.JooqLaborStandardsCatalog
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.DriverManagerDataSource
import javax.sql.DataSource

@Configuration
class LaborServiceConfig {

    @Bean
    fun laborDataSource(
        @Value("\${labor.db.url}") url: String,
        @Value("\${labor.db.username}") username: String,
        @Value("\${labor.db.password}") password: String,
    ): DataSource = DriverManagerDataSource().apply {
        this.setDriverClassName("org.postgresql.Driver")
        this.url = url
        this.username = username
        this.password = password
    }

    @Bean
    fun laborDslContext(dataSource: DataSource): DSLContext =
        DSL.using(dataSource, org.jooq.SQLDialect.POSTGRES)

    @Bean
    fun laborStandardsCatalog(dsl: DSLContext): LaborStandardsCatalog {
        // Swap between JooqLaborStandardsCatalog and InMemoryLaborStandardsCatalog
        // depending on environment. For now, we wrap the Jooq version.
        return JooqLaborStandardsCatalog(dsl)
    }

    @Bean
    fun laborStandardsContextProvider(catalog: LaborStandardsCatalog): LaborStandardsContextProvider =
        CatalogBackedLaborStandardsContextProvider(catalog)
}
