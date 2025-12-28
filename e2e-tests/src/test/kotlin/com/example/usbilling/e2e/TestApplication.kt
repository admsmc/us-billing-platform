package com.example.usbilling.e2e

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.TestConfiguration

/**
 * Minimal Spring Boot test application for E2E tests.
 * 
 * This provides Spring context for the tests but does not start
 * any actual services - tests interact with externally deployed services.
 */
@TestConfiguration
@SpringBootApplication
class TestApplication
