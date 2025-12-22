package com.example.uspayroll.labor.tenancy

import com.example.uspayroll.tenancy.db.TenantDataSourceFactory
import com.example.uspayroll.tenancy.db.TenantDataSources
import com.example.uspayroll.tenancy.db.TenantDbConfig
import com.example.uspayroll.tenancy.db.TenantFlywayMigrator
import com.example.uspayroll.tenancy.web.EmployerTenantWebMvcInterceptor
import jakarta.validation.Valid
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
    @field:Valid
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
class LaborTenancyConfig : WebMvcConfigurer {

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
    fun laborDataSource(tenants: TenantDataSources): DataSource = TenantDataSourceFactory.routing(tenants.byTenant)

    @Bean
    @ConditionalOnProperty(prefix = "tenancy", name = ["mode"], havingValue = "DB_PER_EMPLOYER")
    fun laborDslContext(tenants: TenantDataSources): DSLContext {
        val sampleUrl = tenants.byTenant.values.first().connection.use { it.metaData.url }
        val dialect = if (sampleUrl.startsWith("jdbc:h2:")) org.jooq.SQLDialect.H2 else org.jooq.SQLDialect.POSTGRES
        return DSL.using(laborDataSource(tenants), dialect)
    }

    @Bean
    @ConditionalOnProperty(prefix = "tenancy", name = ["mode"], havingValue = "DB_PER_EMPLOYER")
    fun flywayMigrationStrategy(tenants: TenantDataSources): FlywayMigrationStrategy = FlywayMigrationStrategy {
        TenantFlywayMigrator.migrateAll(tenants, "classpath:db/migration/labor")
    }
}
