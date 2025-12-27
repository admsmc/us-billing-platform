package com.example.usbilling.tax.tenancy

import com.example.usbilling.tenancy.db.TenantDataSourceFactory
import com.example.usbilling.tenancy.db.TenantDataSources
import com.example.usbilling.tenancy.db.TenantDbConfig
import com.example.usbilling.tenancy.db.TenantFlywayMigrator
import com.example.usbilling.tenancy.web.EmployerTenantWebMvcInterceptor
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.validation.annotation.Validated
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import javax.sql.DataSource

@Validated
@ConfigurationProperties(prefix = "tenancy")
data class TenancyProperties(
    @field:NotBlank
    @field:Pattern(regexp = "(SINGLE|DB_PER_EMPLOYER)", message = "tenancy.mode must be SINGLE or DB_PER_EMPLOYER")
    var mode: String = "SINGLE",
    var databases: Map<String, TenantDatabaseProperties> = emptyMap(),
)
data class TenantDatabaseProperties(
    @field:NotBlank
    var url: String = "",
    var username: String = "",
    var password: String = "",
)

@Configuration
@EnableConfigurationProperties(TenancyProperties::class)
class TaxTenancyConfig : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(EmployerTenantWebMvcInterceptor())
    }

    @Bean
    @ConditionalOnProperty(prefix = "tenancy", name = ["mode"], havingValue = "DB_PER_EMPLOYER")
    fun tenantDataSources(props: TenancyProperties): TenantDataSources {
        val byTenant: Map<String, DataSource> = props.databases.mapValues { (tenant, cfg) ->
            TenantDataSourceFactory.buildHikari(
                tenant = tenant,
                cfg = TenantDbConfig(
                    url = cfg.url,
                    username = cfg.username,
                    password = cfg.password,
                    driverClassName = when {
                        cfg.url.startsWith("jdbc:h2:") -> "org.h2.Driver"
                        cfg.url.startsWith("jdbc:postgresql:") -> "org.postgresql.Driver"
                        else -> null
                    },
                ),
            )
        }
        return TenantDataSources(byTenant)
    }

    @Bean
    @ConditionalOnProperty(prefix = "tenancy", name = ["mode"], havingValue = "DB_PER_EMPLOYER")
    fun dataSource(tenants: TenantDataSources): DataSource = TenantDataSourceFactory.routing(tenants.byTenant)

    @Bean
    @ConditionalOnProperty(prefix = "tenancy", name = ["mode"], havingValue = "DB_PER_EMPLOYER")
    fun dslContext(tenants: TenantDataSources): DSLContext {
        // Require that all tenant DBs for this service use the same dialect.
        val sampleUrl = tenants.byTenant.values.first().connection.use { it.metaData.url }

        val dialect = if (sampleUrl.startsWith("jdbc:h2:")) org.jooq.SQLDialect.H2 else org.jooq.SQLDialect.POSTGRES
        return DSL.using(dataSource(tenants), dialect)
    }

    @Bean
    @ConditionalOnProperty(prefix = "tenancy", name = ["mode"], havingValue = "DB_PER_EMPLOYER")
    fun flywayMigrationStrategy(tenants: TenantDataSources): FlywayMigrationStrategy = FlywayMigrationStrategy {
        TenantFlywayMigrator.migrateAll(tenants, "classpath:db/migration/tax")
    }
}
