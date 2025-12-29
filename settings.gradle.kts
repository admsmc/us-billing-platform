pluginManagement {
    repositories {
        gradlePluginPortal()
        maven { url = uri("https://repo.maven.apache.org/maven2/") }
        google()
    }
}

rootProject.name = "us-billing-platform"

include(
    "shared-kernel",
    // "payroll-domain", // REMOVED - Phase 3C migration complete
    "billing-domain",
    "billing-jackson",
    "billing-jackson-spring",
    "customer-domain",
    "time-domain",
    // "rate-domain", // REMOVED - directory does not exist
    "persistence-core",
    "messaging-core",
    "web-core",
    "tenancy-core",
    "edge-service",
    "customer-api",
    "customer-client",
    "customer-service",
    "meter-reading-service",
    "rate-api",
    "rate-config",
    "rate-catalog-ports",
    "rate-impl",
    "rate-content",
    "rate-service",
    "regulatory-api",
    "regulatory-service",
    "billing-orchestrator-service",
    "billing-worker-service",
    "billing-benchmarks",
    "payments-service",
    "filings-service",
    "reporting-service",
    // Customer service platform services (Phase 1)
    "customer-portal-service",
    "notification-service",
    "document-service",
    "case-management-service",
    // CSR tools (Phase 3)
    "csr-portal-service",
    "e2e-tests",
)
