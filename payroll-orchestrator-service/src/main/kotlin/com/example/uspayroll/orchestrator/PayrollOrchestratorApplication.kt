package com.example.uspayroll.orchestrator

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class PayrollOrchestratorApplication

fun main(args: Array<String>) {
    runApplication<PayrollOrchestratorApplication>(*args)
}
