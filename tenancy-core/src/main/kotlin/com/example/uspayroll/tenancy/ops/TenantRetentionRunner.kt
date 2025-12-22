package com.example.uspayroll.tenancy.ops

import com.example.uspayroll.tenancy.db.TenantDataSources
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

/**
 * Generic SQL retention runner for DB-per-employer services.
 *
 * This is intentionally narrow:
 * - deletes are optional (dry-run supported)
 * - rules operate on a single table + timestamp column
 * - callers must supply safe predicates (no user input)
 */
object TenantRetentionRunner {

    data class SqlRetentionRule(
        val id: String,
        val table: String,
        val timestampColumn: String,
        val retention: Duration,
        val extraWhereClause: String? = null,
    ) {
        init {
            require(id.isNotBlank())
            require(table.isNotBlank())
            require(timestampColumn.isNotBlank())
            require(!retention.isNegative && !retention.isZero)
        }

        fun whereClause(): String {
            val base = "$timestampColumn < ?"
            val extra = extraWhereClause?.trim()?.takeIf { it.isNotBlank() }
            return if (extra == null) base else "$base AND ($extra)"
        }
    }

    data class RuleRunResult(
        val tenant: String,
        val ruleId: String,
        val cutoff: Instant,
        val eligibleRows: Long,
        val deletedRows: Long? = null,
    )

    fun dryRun(dataSources: TenantDataSources, rules: List<SqlRetentionRule>, now: Instant = Instant.now()): List<RuleRunResult> = runInternal(dataSources, rules, now, applyDeletes = false)

    fun apply(dataSources: TenantDataSources, rules: List<SqlRetentionRule>, now: Instant = Instant.now()): List<RuleRunResult> = runInternal(dataSources, rules, now, applyDeletes = true)

    private fun runInternal(dataSources: TenantDataSources, rules: List<SqlRetentionRule>, now: Instant, applyDeletes: Boolean): List<RuleRunResult> {
        require(rules.isNotEmpty()) { "rules must be non-empty" }

        val out = mutableListOf<RuleRunResult>()

        dataSources.byTenant.forEach { (tenant, ds) ->
            ds.connection.use { conn ->
                conn.autoCommit = true

                rules.forEach { rule ->
                    val cutoff = now.minus(rule.retention)
                    val where = rule.whereClause()

                    val eligible = conn.prepareStatement("SELECT COUNT(*) FROM ${rule.table} WHERE $where").use { ps ->
                        ps.setTimestamp(1, Timestamp.from(cutoff))
                        ps.executeQuery().use { rs ->
                            rs.next()
                            rs.getLong(1)
                        }
                    }

                    if (!applyDeletes) {
                        out += RuleRunResult(tenant = tenant, ruleId = rule.id, cutoff = cutoff, eligibleRows = eligible)
                    } else {
                        val deleted = conn.prepareStatement("DELETE FROM ${rule.table} WHERE $where").use { ps ->
                            ps.setTimestamp(1, Timestamp.from(cutoff))
                            ps.executeUpdate().toLong()
                        }
                        out += RuleRunResult(tenant = tenant, ruleId = rule.id, cutoff = cutoff, eligibleRows = eligible, deletedRows = deleted)
                    }
                }
            }
        }

        return out
    }
}
