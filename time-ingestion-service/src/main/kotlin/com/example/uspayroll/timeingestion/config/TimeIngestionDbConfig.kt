package com.example.uspayroll.timeingestion.config

import com.example.uspayroll.persistence.flyway.FlywaySupport
import com.example.uspayroll.tenancy.db.TenantDataSourceFactory
import com.example.uspayroll.tenancy.db.TenantDbConfig
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource

@ConfigurationProperties(prefix = "time.db")
data class TimeDbProperties(
    var url: String = "",
    var username: String = "",
    var password: String = "",
)

@Configuration
@EnableConfigurationProperties(TimeDbProperties::class)
class TimeIngestionDbConfig {

    @Bean
    @ConditionalOnProperty(prefix = "tenancy", name = ["mode"], havingValue = "SINGLE", matchIfMissing = true)
    fun timeDataSource(props: TimeDbProperties): DataSource {
        val driverClassName = when {
            props.url.startsWith("jdbc:h2:") -> "org.h2.Driver"
            props.url.startsWith("jdbc:postgresql:") -> "org.postgresql.Driver"
            else -> null
        }

        return TenantDataSourceFactory.buildHikari(
            tenant = "time-single",
            cfg = TenantDbConfig(
                url = props.url,
                username = props.username,
                password = props.password,
                driverClassName = driverClassName,
            ),
        )
    }

    @Bean
    fun timeJdbcTemplate(ds: DataSource): JdbcTemplate = JdbcTemplate(ds)

    /**
     * Apply migrations on startup.
     *
     * We run Flyway explicitly (rather than Boot autoconfig) so the migration location is unambiguous.
     */
    @Bean
    @ConditionalOnProperty(prefix = "tenancy", name = ["mode"], havingValue = "SINGLE", matchIfMissing = true)
    fun timeFlywayMigrate(ds: DataSource): Any {
        FlywaySupport.migrate(ds, "classpath:db/migration/time")
        return Any()
    }
}
