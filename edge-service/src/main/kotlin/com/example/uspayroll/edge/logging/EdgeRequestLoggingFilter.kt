package com.example.uspayroll.edge.logging

import com.example.uspayroll.web.WebHeaders
import com.example.uspayroll.web.WebMdcKeys
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class EdgeRequestLoggingFilter : GlobalFilter {

    private val logger = LoggerFactory.getLogger(EdgeRequestLoggingFilter::class.java)

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val startNanos = System.nanoTime()

        return chain.filter(exchange).doFinally {
            val correlationId = exchange.request.headers.getFirst(WebHeaders.CORRELATION_ID)
            val employerId = exchange.request.headers.getFirst(WebHeaders.EMPLOYER_ID)
            val principalSub = exchange.request.headers.getFirst(WebHeaders.PRINCIPAL_SUB)

            val method = exchange.request.method?.name() ?: ""
            val path = exchange.request.uri.path
            val status = exchange.response.statusCode?.value() ?: 0
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000

            fun putIfPresent(mdcKey: String, value: String?) {
                if (!value.isNullOrBlank()) MDC.put(mdcKey, value)
            }

            putIfPresent(WebMdcKeys.CORRELATION_ID, correlationId)
            putIfPresent(WebMdcKeys.EMPLOYER_ID, employerId)
            putIfPresent(WebMdcKeys.PRINCIPAL_SUB, principalSub)

            try {
                if (status >= 500) {
                    logger.warn(
                        "http_request method={} path={} status={} elapsedMs={}",
                        method,
                        path,
                        status,
                        elapsedMs,
                    )
                } else {
                    logger.info(
                        "http_request method={} path={} status={} elapsedMs={}",
                        method,
                        path,
                        status,
                        elapsedMs,
                    )
                }
            } finally {
                MDC.remove(WebMdcKeys.CORRELATION_ID)
                MDC.remove(WebMdcKeys.EMPLOYER_ID)
                MDC.remove(WebMdcKeys.PRINCIPAL_SUB)
            }
        }
    }
}
