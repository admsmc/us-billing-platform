package com.example.usbilling.worker.support

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.util.UUID

@ConfigurationProperties(prefix = "worker.instance")
data class WorkerInstanceProperties(
    /**
     * Optional stable identifier for this worker instance.
     *
     * In Kubernetes, setting this to the Pod name (often available via HOSTNAME)
     * is a good default.
     */
    var id: String? = null,
)

@Component
@EnableConfigurationProperties(WorkerInstanceProperties::class)
class WorkerInstance(
    props: WorkerInstanceProperties,
) {
    val instanceId: String = computeInstanceId(props)

    /**
     * Lease owner value sent to orchestrator /execute. Must be unique per worker instance.
     */
    val leaseOwner: String = "worker-$instanceId"

    private fun computeInstanceId(props: WorkerInstanceProperties): String {
        val configured = props.id?.trim().orEmpty()
        if (configured.isNotBlank()) return sanitize(configured)

        val host = (System.getenv("HOSTNAME")?.trim().orEmpty()).ifBlank {
            runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.trim().orEmpty()
        }.ifBlank {
            "unknown-host"
        }

        // Add a short random suffix so multiple workers on the same host don't collide.
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
        return "${sanitize(host)}-$suffix"
    }

    private fun sanitize(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-z0-9-]+"), "-")
        .trim('-')
        .ifBlank { "worker" }
}
