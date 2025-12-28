package com.example.usbilling.tax

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Rate Service - manages utility rate tariffs and pricing schedules.
 *
 * This service owns the rate catalog bounded context and provides HTTP APIs for:
 * - Rate tariffs (flat, tiered, time-of-use, demand rates)
 * - Rate schedules and seasonal variations
 * - Customer class-based rate assignment
 * - Effective-dated rate applicability
 *
 * Implements RateContextProvider port from rate-api.
 */
@SpringBootApplication(scanBasePackages = ["com.example.usbilling"])
class RateServiceApplication

fun main(args: Array<String>) {
    runApplication<RateServiceApplication>(*args)
}
