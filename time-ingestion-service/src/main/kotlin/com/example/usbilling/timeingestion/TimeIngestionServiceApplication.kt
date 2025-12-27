package com.example.usbilling.timeingestion

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class TimeIngestionServiceApplication

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<TimeIngestionServiceApplication>(*args)
}
