package com.example.usbilling.orchestrator

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class PayrollOrchestratorApplication

fun main(args: Array<String>) {
    runApplication<PayrollOrchestratorApplication>(*args)
}
