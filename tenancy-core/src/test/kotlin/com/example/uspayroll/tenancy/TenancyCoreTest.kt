package com.example.uspayroll.tenancy

import com.example.uspayroll.tenancy.db.TenantDataSourceFactory
import com.example.uspayroll.tenancy.db.TenantDbConfig
import com.example.uspayroll.tenancy.web.EmployerTenantWebMvcInterceptor
import com.example.uspayroll.web.WebHeaders
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.servlet.HandlerMapping

class TenancyCoreTest {

    @Test
    fun `TenantContext restores previous tenant`() {
        TenantContext.clear()
        TenantContext.set("A")

        val value = TenantContext.withTenant("B") {
            assertEquals("B", TenantContext.require())
            "ok"
        }

        assertEquals("ok", value)
        assertEquals("A", TenantContext.require())
    }

    @Test
    fun `routing datasource selects correct tenant db`() {
        val dsA = TenantDataSourceFactory.buildHikari(
            tenant = "A",
            cfg = TenantDbConfig(
                url = "jdbc:h2:mem:tenant_a;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                username = "sa",
                password = "",
                driverClassName = "org.h2.Driver",
            ),
        )
        val dsB = TenantDataSourceFactory.buildHikari(
            tenant = "B",
            cfg = TenantDbConfig(
                url = "jdbc:h2:mem:tenant_b;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                username = "sa",
                password = "",
                driverClassName = "org.h2.Driver",
            ),
        )

        val routing = TenantDataSourceFactory.routing(mapOf("A" to dsA, "B" to dsB))

        TenantContext.withTenant("A") {
            routing.connection.use { conn ->
                conn.createStatement().use { st ->
                    st.executeUpdate("CREATE TABLE t (id INT PRIMARY KEY)")
                }
            }
        }

        TenantContext.withTenant("B") {
            routing.connection.use { conn ->
                conn.createStatement().use { st ->
                    // Should not see table from tenant A.
                    st.executeUpdate("CREATE TABLE t (id INT PRIMARY KEY)")
                }
            }
        }

        // Sanity: no tenant -> should throw.
        TenantContext.clear()
        var threw = false
        try {
            routing.connection.close()
        } catch (_: IllegalArgumentException) {
            threw = true
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertEquals(true, threw)
    }

    @Test
    fun `web interceptor sets tenant and validates header matches`() {
        val interceptor = EmployerTenantWebMvcInterceptor()
        val req = MockHttpServletRequest("GET", "/employers/EMP1/pay-periods/PP")
        val resp = MockHttpServletResponse()

        req.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, mapOf("employerId" to "EMP1"))
        req.addHeader(WebHeaders.EMPLOYER_ID, "EMP1")

        val ok = interceptor.preHandle(req, resp, Any())
        assertEquals(true, ok)
        assertEquals("EMP1", TenantContext.require())

        interceptor.afterCompletion(req, resp, Any(), null)
        assertEquals(null, TenantContext.get())

        val badReq = MockHttpServletRequest("GET", "/employers/EMP1/pay-periods/PP")
        val badResp = MockHttpServletResponse()
        badReq.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, mapOf("employerId" to "EMP1"))
        badReq.addHeader(WebHeaders.EMPLOYER_ID, "EMP2")

        val ok2 = interceptor.preHandle(badReq, badResp, Any())
        assertEquals(false, ok2)
        assertEquals(400, badResp.status)
        assertNotNull(badResp.contentAsString)
    }
}
