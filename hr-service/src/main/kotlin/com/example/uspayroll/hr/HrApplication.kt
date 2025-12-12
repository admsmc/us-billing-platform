package com.example.uspayroll.hr

import com.example.uspayroll.hr.garnishment.GarnishmentLedgerRepository
import com.example.uspayroll.hr.garnishment.JdbcGarnishmentLedgerRepository
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean

@SpringBootApplication
class HrApplication {

    @Bean
    fun garnishmentLedgerRepository(jdbcTemplate: org.springframework.jdbc.core.JdbcTemplate): GarnishmentLedgerRepository =
        JdbcGarnishmentLedgerRepository(jdbcTemplate)
}

fun main(args: Array<String>) {
    runApplication<HrApplication>(*args)
}