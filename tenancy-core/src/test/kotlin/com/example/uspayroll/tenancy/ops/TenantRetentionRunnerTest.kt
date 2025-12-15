package com.example.uspayroll.tenancy.ops

import com.example.uspayroll.tenancy.db.TenantDataSources
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class TenantRetentionRunnerTest {

    @Test
    fun `dryRun and apply compute eligible rows per tenant`() {
        val now = Instant.parse("2025-01-15T00:00:00Z")

        val ds1 = JdbcDataSource().apply { setURL("jdbc:h2:mem:t1;MODE=PostgreSQL;DB_CLOSE_DELAY=-1") }
        val ds2 = JdbcDataSource().apply { setURL("jdbc:h2:mem:t2;MODE=PostgreSQL;DB_CLOSE_DELAY=-1") }

        fun seed(ds: JdbcDataSource) {
            ds.connection.use { c ->
                c.createStatement().use { st ->
                    st.execute("CREATE TABLE outbox_event (outbox_id VARCHAR(128) PRIMARY KEY, created_at TIMESTAMP NOT NULL)")
                    st.execute("INSERT INTO outbox_event(outbox_id, created_at) VALUES ('old', TIMESTAMP '2024-01-01 00:00:00')")
                    st.execute("INSERT INTO outbox_event(outbox_id, created_at) VALUES ('new', TIMESTAMP '2025-01-14 00:00:00')")
                }
            }
        }

        seed(ds1)
        seed(ds2)

        val tenants = TenantDataSources(
            byTenant = mapOf(
                "EMP1" to ds1,
                "EMP2" to ds2,
            ),
        )

        val rules = listOf(
            TenantRetentionRunner.SqlRetentionRule(
                id = "outbox_old",
                table = "outbox_event",
                timestampColumn = "created_at",
                retention = Duration.ofDays(30),
            ),
        )

        val dry = TenantRetentionRunner.dryRun(tenants, rules, now)
        assertEquals(2, dry.size)
        assertEquals(1, dry.first { it.tenant == "EMP1" }.eligibleRows)
        assertEquals(1, dry.first { it.tenant == "EMP2" }.eligibleRows)

        val applied = TenantRetentionRunner.apply(tenants, rules, now)
        assertEquals(1, applied.first { it.tenant == "EMP1" }.deletedRows)
        assertEquals(1, applied.first { it.tenant == "EMP2" }.deletedRows)

        val dryAfter = TenantRetentionRunner.dryRun(tenants, rules, now)
        assertEquals(0, dryAfter.first { it.tenant == "EMP1" }.eligibleRows)
        assertEquals(0, dryAfter.first { it.tenant == "EMP2" }.eligibleRows)
    }
}
