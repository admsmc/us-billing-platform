package com.example.uspayroll.tax.config

import com.example.uspayroll.tax.api.TaxCatalog
import com.example.uspayroll.tax.api.TaxContextProvider
import com.example.uspayroll.tax.impl.CachingTaxCatalog
import com.example.uspayroll.tax.impl.CatalogBackedTaxContextProvider
import com.example.uspayroll.tax.impl.DbTaxCatalog
import com.example.uspayroll.tax.impl.TaxRuleRepository
import com.example.uspayroll.tax.persistence.JooqTaxRuleRepository
import com.example.uspayroll.tax.service.DefaultFederalWithholdingCalculator
import com.example.uspayroll.tax.service.FederalWithholdingCalculator
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.DriverManagerDataSource
import javax.sql.DataSource

/**
 * Spring configuration for tax-service wiring.
 *
 * This keeps the wiring of adapters and caching behavior in the service layer
 * while the underlying payroll-domain and tax-domain modules remain
 * framework-free.
 */
@Configuration
class TaxServiceConfig {

    @Bean
    fun dataSource(
        @Value("\${tax.db.url}") url: String,
        @Value("\${tax.db.username}") username: String,
        @Value("\${tax.db.password}") password: String,
    ): DataSource = DriverManagerDataSource().apply {
        this.setDriverClassName("org.postgresql.Driver")
        this.url = url
        this.username = username
        this.password = password
    }

    @Bean
    fun dslContext(dataSource: DataSource): DSLContext =
        DSL.using(dataSource, org.jooq.SQLDialect.POSTGRES)

    @Bean
    fun taxRuleRepository(dsl: DSLContext): TaxRuleRepository =
        JooqTaxRuleRepository(dsl)

    @Bean
    fun taxCatalog(repository: TaxRuleRepository): TaxCatalog {
        val dbCatalog = DbTaxCatalog(repository)
        return CachingTaxCatalog(dbCatalog)
    }

    @Bean
    fun taxContextProvider(catalog: TaxCatalog): TaxContextProvider {
        return CatalogBackedTaxContextProvider(catalog)
    }

    @Bean
    fun federalWithholdingCalculator(
        @Value("\${tax.federalWithholding.method:PERCENTAGE}") method: String,
        @Value("\${tax.federalWithholding.pub15tStrictMode:false}") strictMode: Boolean,
    ): FederalWithholdingCalculator =
        DefaultFederalWithholdingCalculator(
            method = method,
            pub15tStrictMode = strictMode,
        )
}
