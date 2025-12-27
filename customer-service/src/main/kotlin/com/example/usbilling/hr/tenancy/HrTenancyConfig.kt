package com.example.usbilling.hr.tenancy

import com.example.usbilling.tenancy.db.TenantDataSourceFactory
import com.example.usbilling.tenancy.db.TenantDataSources
import com.example.usbilling.tenancy.db.TenantDbConfig
import com.example.usbilling.tenancy.db.TenantFlywayMigrator
import com.example.usbilling.tenancy.web.EmployerTenantWebMvcInterceptor
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
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
    /** Valid values: SINGLE, DB_PER_EMPLOYER */
    @field:NotBlank
    @field:Pattern(regexp = "(SINGLE|DB_PER_EMPLOYER)", message = "tenancy.mode must be SINGLE or DB_PER_EMPLOYER")
    var mode: String = "SINGLE",
    /** Map from employerId -> DB config */
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
class HrTenancyConfig : WebMvcConfigurer {

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
    fun flywayMigrationStrategy(tenants: TenantDataSources): FlywayMigrationStrategy = FlywayMigrationStrategy {
        // Migrate all tenant HR databases.
        TenantFlywayMigrator.migrateAll(tenants, "classpath:db/migration/hr")
        // Do not run Flyway against the routing DataSource.
    }
}
