package com.example.uspayroll.hr.garnishment

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.stereotype.Component
import kotlin.system.exitProcess

@Component
@ConditionalOnProperty(
    prefix = "hr.audit.garnishment-overrides",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
@EnableConfigurationProperties(GarnishmentOverrideAuditProperties::class)
class GarnishmentOverrideAuditRunner(
    private val props: GarnishmentOverrideAuditProperties,
    private val auditService: GarnishmentOrderOverrideAuditService,
    private val context: ConfigurableApplicationContext,
) : ApplicationRunner {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val report = auditService.auditAllOrders()

        if (report.isValid) {
            logger.info(
                "hr.audit.garnishment_overrides ok rows_checked={}",
                report.rowsChecked,
            )
        } else {
            logger.error(
                "hr.audit.garnishment_overrides failed rows_checked={} errors={}",
                report.rowsChecked,
                report.errors.size,
            )
            report.errors
                .take(200)
                .forEach { e ->
                    logger.error("hr.audit.garnishment_overrides.error orderId={} msg={}", e.orderId, e.message)
                }
        }

        if (props.exitAfterRun) {
            val exitCode = if (report.isValid) 0 else 1
            // Ensure Spring shuts down cleanly before exiting.
            context.close()
            exitProcess(exitCode)
        }
    }
}
