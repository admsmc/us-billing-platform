package com.example.uspayroll.payments.tenancy

import com.example.uspayroll.tenancy.db.TenantDataSourceFactory
import com.example.uspayroll.tenancy.db.TenantDataSources
import com.example.uspayroll.tenancy.db.TenantDbConfig
import com.example.uspayroll.tenancy.db.TenantFlywayMigrator
import com.example.uspayroll.tenancy.web.EmployerTenantWebMvcInterceptor
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import javax.sql.DataSource

@ConfigurationProperties(prefix = "tenancy")
data class TenancyProperties(
    var mode: String = "SINGLE",
    var databases: Map<String, TenantDatabaseProperties> = emptyMap(),
)

data class TenantDatabaseProperties(
    var url: String = "",
    var username: String = "",
    var password: String = "",
)

@Configuration
@EnableConfigurationProperties(TenancyProperties::class)
class PaymentsTenancyConfig : WebMvcConfigurer {

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
    fun dataSource(tenants: TenantDataSources): DataSource {
        return TenantDataSourceFactory.routing(tenants.byTenant)
    }

    @Bean
    @ConditionalOnProperty(prefix = "tenancy", name = ["mode"], havingValue = "DB_PER_EMPLOYER")
    fun flywayMigrationStrategy(tenants: TenantDataSources): FlywayMigrationStrategy {
        return FlywayMigrationStrategy {
            TenantFlywayMigrator.migrateAll(tenants, "classpath:db/migration")
        }
    }
}
