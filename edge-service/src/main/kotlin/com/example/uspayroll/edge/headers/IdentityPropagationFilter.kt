package com.example.uspayroll.edge.headers

import com.example.uspayroll.web.WebHeaders
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
            if (!exchangeWithCorrelation.response.headers.containsKey(WebHeaders.CORRELATION_ID)) {
                exchangeWithCorrelation.response.headers.add(WebHeaders.CORRELATION_ID, correlationId)
            }
            Mono.empty()
        }

        return exchangeWithCorrelation.getPrincipal<Authentication>()
            .flatMap { auth ->
                val jwt = auth.principal as? Jwt
                if (jwt == null) {
                    chain.filter(exchangeWithCorrelation).thenReturn(Unit)
                } else {
                    val mutated = exchangeWithCorrelation.mutate()
                        .request(
                            exchangeWithCorrelation.request
                                .mutate()
                                .header(WebHeaders.PRINCIPAL_SUB, jwt.subject ?: "")
                                .header(WebHeaders.PRINCIPAL_SCOPE, jwt.getClaimAsString("scope") ?: "")
                                .header(WebHeaders.EMPLOYER_ID, jwt.getClaimAsString("employer_id") ?: "")
                                .build(),
                        )
                        .build()
                    chain.filter(mutated).thenReturn(Unit)
                }
            }
            .switchIfEmpty(chain.filter(exchangeWithCorrelation).thenReturn(Unit))
            .then()
    }

    private fun ensureCorrelationId(exchange: ServerWebExchange): Pair<ServerWebExchange, String> {
        val existing = exchange.request.headers.getFirst(WebHeaders.CORRELATION_ID)
        val correlationId = if (existing.isNullOrBlank()) UUID.randomUUID().toString() else existing

        if (!existing.isNullOrBlank()) {
            return exchange to correlationId
        }

        val mutatedRequest: ServerHttpRequest = exchange.request
            .mutate()
            .header(WebHeaders.CORRELATION_ID, correlationId)
            .build()

        return exchange.mutate().request(mutatedRequest).build() to correlationId
    }
}
