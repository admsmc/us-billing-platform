package com.example.usbilling.edge.headers

import com.example.usbilling.web.WebHeaders
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
                    val pathEmployerId = employerIdFromPath(exchangeWithCorrelation.request.uri.path)
                    val employerId = pathEmployerId
                        ?: jwt.getClaimAsString("employer_id")?.trim()?.takeIf { it.isNotBlank() }

                    val principalSub = jwt.subject?.trim().orEmpty()
                    val principalScope = normalizeScopes(jwt)

                    val mutatedRequest: ServerHttpRequest = exchangeWithCorrelation.request
                        .mutate()
                        .headers { headers ->
                            // These headers are an edge-owned contract; prevent client injection.
                            headers.remove(WebHeaders.PRINCIPAL_SUB)
                            headers.remove(WebHeaders.PRINCIPAL_SCOPE)
                            headers.remove(WebHeaders.EMPLOYER_ID)
                        }
                        .apply {
                            if (principalSub.isNotBlank()) header(WebHeaders.PRINCIPAL_SUB, principalSub)
                            if (principalScope.isNotBlank()) header(WebHeaders.PRINCIPAL_SCOPE, principalScope)
                            if (!employerId.isNullOrBlank()) header(WebHeaders.EMPLOYER_ID, employerId)
                        }
                        .build()

                    val mutated = exchangeWithCorrelation.mutate().request(mutatedRequest).build()
                    chain.filter(mutated).thenReturn(Unit)
                }
            }
            .switchIfEmpty(chain.filter(exchangeWithCorrelation).thenReturn(Unit))
            .then()
    }

    private fun normalizeScopes(jwt: Jwt): String {
        val out = mutableSetOf<String>()

        jwt.getClaimAsString("scope")
            ?.split(" ")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.forEach { out += it }

        jwt.getClaimAsStringList("scp")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.forEach { out += it }

        return out.sorted().joinToString(" ")
    }

    private fun employerIdFromPath(path: String): String? {
        val regex = Regex("^/employers/([^/]+)(/.*)?$")
        val m = regex.matchEntire(path) ?: return null
        return m.groupValues[1].trim().takeIf { it.isNotBlank() }
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
