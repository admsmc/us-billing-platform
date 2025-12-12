package com.example.uspayroll.edge.headers

import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.util.UUID

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class IdentityPropagationFilter : GlobalFilter {

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val (exchangeWithCorrelation, correlationId) = ensureCorrelationId(exchange)

        // Echo correlation id for easier debugging.
        exchangeWithCorrelation.response.beforeCommit {
            if (!exchangeWithCorrelation.response.headers.containsKey(HEADER_CORRELATION_ID)) {
                exchangeWithCorrelation.response.headers.add(HEADER_CORRELATION_ID, correlationId)
            }
            Mono.empty()
        }

        return exchangeWithCorrelation.getPrincipal<Authentication>()
            .flatMap { auth ->
                val jwt = auth.principal as? Jwt
                if (jwt == null) {
                    chain.filter(exchangeWithCorrelation)
                } else {
                    val mutated = exchangeWithCorrelation.mutate()
                        .request(
                            exchangeWithCorrelation.request
                                .mutate()
                                .header(HEADER_PRINCIPAL_SUB, jwt.subject ?: "")
                                .header(HEADER_PRINCIPAL_SCOPE, jwt.getClaimAsString("scope") ?: "")
                                .header(HEADER_EMPLOYER_ID, jwt.getClaimAsString("employer_id") ?: "")
                                .build(),
                        )
                        .build()
                    chain.filter(mutated)
                }
            }
            .switchIfEmpty(chain.filter(exchangeWithCorrelation))
    }

    private fun ensureCorrelationId(exchange: ServerWebExchange): Pair<ServerWebExchange, String> {
        val existing = exchange.request.headers.getFirst(HEADER_CORRELATION_ID)
        val correlationId = if (existing.isNullOrBlank()) UUID.randomUUID().toString() else existing

        if (!existing.isNullOrBlank()) {
            return exchange to correlationId
        }

        val mutatedRequest: ServerHttpRequest = exchange.request
            .mutate()
            .header(HEADER_CORRELATION_ID, correlationId)
            .build()

        return exchange.mutate().request(mutatedRequest).build() to correlationId
    }

    private companion object {
        private const val HEADER_CORRELATION_ID = "X-Correlation-ID"

        // First-pass identity propagation headers.
        private const val HEADER_PRINCIPAL_SUB = "X-Principal-Sub"
        private const val HEADER_PRINCIPAL_SCOPE = "X-Principal-Scope"
        private const val HEADER_EMPLOYER_ID = "X-Employer-Id"
    }
}
