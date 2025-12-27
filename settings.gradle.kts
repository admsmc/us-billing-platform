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
    "payroll-domain",
    "billing-domain",
    "billing-jackson",
    "billing-jackson-spring",
    "hr-domain",
    "time-domain",
    "tax-domain",
    "persistence-core",
    "messaging-core",
    "web-core",
    "tenancy-core",
    "edge-service",
    "hr-api",
    "hr-client",
    "hr-service",
    "time-ingestion-service",
    "tax-api",
    "tax-config",
    "tax-catalog-ports",
    "tax-impl",
    "tax-content",
    "tax-service",
    "labor-api",
    "labor-service",
    "billing-orchestrator-service",
    "billing-worker-service",
    "billing-benchmarks",
    "payments-service",
    "filings-service",
    "reporting-service",
)
