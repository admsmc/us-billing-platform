package com.example.usbilling.orchestrator.ops

import com.example.usbilling.tenancy.db.TenantDataSources
import com.example.usbilling.tenancy.ops.TenantRetentionRunner
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource

@ConfigurationProperties(prefix = "orchestrator.retention.outbox")
data class OrchestratorOutboxRetentionProperties(
    /** Opt-in only; disabled by default. */
    var enabled: Boolean = false,
    /** If false, perform dry-run only (counts). */
    var applyDeletes: Boolean = false,
    /** Retain SENT outbox rows for this many days. */
    var retentionDays: Long = 30,
    /** Fixed delay between runs. */
    var fixedDelayMillis: Long = 60 * 60 * 1000L,
)

@Configuration
@EnableConfigurationProperties(OrchestratorOutboxRetentionProperties::class)
class OrchestratorOutboxRetentionConfig

@Component
@ConditionalOnProperty(prefix = "orchestrator.retention.outbox", name = ["enabled"], havingValue = "true")
class OutboxRetentionJob(
    private val props: OrchestratorOutboxRetentionProperties,
    tenantDataSourcesProvider: ObjectProvider<TenantDataSources>,
    private val dataSource: DataSource,
) {

    private val logger = LoggerFactory.getLogger(OutboxRetentionJob::class.java)

    private val tenants: TenantDataSources? = tenantDataSourcesProvider.ifAvailable

    @Scheduled(fixedDelayString = "\${orchestrator.retention.outbox.fixed-delay-millis:3600000}")
    fun tick() {
        val now = Instant.now()

        val retention = Duration.ofDays(props.retentionDays.coerceAtLeast(1))
        val rules = listOf(
            TenantRetentionRunner.SqlRetentionRule(
                id = "orchestrator_outbox_sent",
                table = "outbox_event",
                timestampColumn = "published_at",
                retention = retention,
                // Only delete rows that are durably published.
                extraWhereClause = "status = 'SENT'",
            ),
        )

        val dataSources = tenants
            ?: TenantDataSources(
                byTenant = mapOf(
                    "default" to dataSource,
                ),
            )

        val results = if (props.applyDeletes) {
            TenantRetentionRunner.apply(dataSources, rules, now)
        } else {
            TenantRetentionRunner.dryRun(dataSources, rules, now)
        }

        val totalEligible = results.sumOf { it.eligibleRows }
        val totalDeleted = results.sumOf { it.deletedRows ?: 0L }

        logger.info(
            "retention.outbox.completed apply_deletes={} tenants={} eligible={} deleted={} retention_days={}",
            props.applyDeletes,
            results.map { it.tenant }.distinct().size,
            totalEligible,
            totalDeleted,
            props.retentionDays,
        )
    }
}
