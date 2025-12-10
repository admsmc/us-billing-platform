package com.example.uspayroll.hr

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
open class HrApplication

fun main(args: Array<String>) {
    runApplication<HrApplication>(*args)
}