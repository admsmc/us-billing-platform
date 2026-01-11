package com.example.usbilling.portal

import com.example.usbilling.portal.security.CustomerPrincipal
import com.example.usbilling.portal.security.JwtUtil
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "customer-portal.jwt.secret=test-secret-key-for-integration-tests-must-be-at-least-256-bits-long",
        "customer-portal.case-management-service-url=http://localhost:8092",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "spring.main.allow-bean-definition-overriding=true",
        "org.springframework.boot.test.context.SpringBootTestContextBootstrapper=true",
        "spring.main.web-application-type=none",
    ],
)
@Import(TestConfig::class)
class AuthenticationIntegrationTest {

    @Autowired
    private lateinit var jwtUtil: JwtUtil

    @Test
    fun `should extract customer principal from valid JWT token`() {
        val principal = CustomerPrincipal(
            customerId = "CUST-123",
            utilityId = "UTIL-456",
            accountIds = listOf("ACC-789", "ACC-790"),
            email = "customer@test.com",
            fullName = "Jane Doe",
        )

        val token = jwtUtil.generateToken(principal)
        val claims = jwtUtil.validateAndExtractClaims(token)
        val extractedPrincipal = jwtUtil.extractPrincipal(claims!!)

        assert(extractedPrincipal != null)
        assert(extractedPrincipal?.customerId == "CUST-123")
        assert(extractedPrincipal?.utilityId == "UTIL-456")
        assert(extractedPrincipal?.accountIds == listOf("ACC-789", "ACC-790"))
        assert(extractedPrincipal?.email == "customer@test.com")
        assert(extractedPrincipal?.fullName == "Jane Doe")
    }
}
