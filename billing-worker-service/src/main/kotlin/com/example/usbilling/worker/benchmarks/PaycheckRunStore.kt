package com.example.usbilling.worker.benchmarks

import com.example.usbilling.payroll.model.PaycheckResult
import com.example.usbilling.shared.UtilityId
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Component
class PaycheckRunStore {

    data class StoredRun(
        val employerId: UtilityId,
        val payPeriodId: String,
        val runId: String,
        val createdAt: Instant,
        val paychecks: List<PaycheckResult>,
    )

    data class StoredRunSummary(
        val employerId: String,
        val payPeriodId: String,
        val runId: String,
        val createdAtEpochMillis: Long,
        val paychecks: Int,
    )

    data class Config(
        var enabled: Boolean = true,
        var maxRuns: Int = 8,
        var maxPaychecksPerRun: Int = 5_000,
        var ttlSeconds: Long = 30 * 60, // 30 minutes
    )

    data class Key(
        val employerId: String,
        val runId: String,
    )

    @Volatile
    var config: Config = Config()

    private val runs: ConcurrentHashMap<Key, StoredRun> = ConcurrentHashMap()

    fun put(run: StoredRun) {
        if (!config.enabled) return
        if (run.paychecks.size > config.maxPaychecksPerRun) {
            error("Refusing to store run ${run.runId}: paychecks=${run.paychecks.size} exceeds maxPaychecksPerRun=${config.maxPaychecksPerRun}")
        }

        cleanupExpired(now = Instant.now())

        runs[Key(run.employerId.value, run.runId)] = run

        // Best-effort cap.
        trimToMaxRuns(employerId = run.employerId.value)
    }

    fun get(employerId: UtilityId, runId: String): StoredRun? {
        cleanupExpired(now = Instant.now())
        return runs[Key(employerId.value, runId)]
    }

    fun list(employerId: UtilityId): List<StoredRunSummary> {
        cleanupExpired(now = Instant.now())
        return runs.values
            .asSequence()
            .filter { it.employerId == employerId }
            .sortedByDescending { it.createdAt }
            .map {
                StoredRunSummary(
                    employerId = it.employerId.value,
                    payPeriodId = it.payPeriodId,
                    runId = it.runId,
                    createdAtEpochMillis = it.createdAt.toEpochMilli(),
                    paychecks = it.paychecks.size,
                )
            }
            .toList()
    }

    private fun cleanupExpired(now: Instant) {
        if (!config.enabled) return
        val ttl = config.ttlSeconds
        if (ttl <= 0) return

        val cutoff = now.minusSeconds(ttl)
        for ((k, v) in runs) {
            if (v.createdAt.isBefore(cutoff)) {
                runs.remove(k)
            }
        }
    }

    private fun trimToMaxRuns(employerId: String) {
        val max = config.maxRuns
        if (max <= 0) return

        val keys = runs.entries
            .asSequence()
            .filter { (k, _) -> k.employerId == employerId }
            .sortedByDescending { (_, v) -> v.createdAt }
            .map { (k, _) -> k }
            .toList()

        if (keys.size <= max) return

        for (k in keys.drop(max)) {
            runs.remove(k)
        }
    }
}
