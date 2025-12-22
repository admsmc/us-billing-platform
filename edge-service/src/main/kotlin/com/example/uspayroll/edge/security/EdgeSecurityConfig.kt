package com.example.uspayroll.edge.security

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoders
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.validation.annotation.Validated
import java.nio.charset.StandardCharsets
import javax.crypto.spec.SecretKeySpec

@Validated
@ConfigurationProperties(prefix = "edge.auth")
data class EdgeAuthProperties(
    /**
     * Valid values:
     * - DISABLED: no auth (explicitly for local dev)
     * - HS256: validate JWTs signed with a shared HMAC secret
     * - OIDC: validate JWTs using an issuer URI or JWK set URI
     */
    @field:NotBlank
    @field:Pattern(regexp = "(?i)^(DISABLED|HS256|OIDC)$", message = "edge.auth.mode must be DISABLED, HS256, or OIDC")
    var mode: String = "HS256",

    /**
     * Guardrail for tier-1 posture.
     *
     * If false (recommended), edge-service will refuse to start with DISABLED mode.
     */
    var allowDisabled: Boolean = false,

    /** HS256 shared secret (dev only). */
    var hs256Secret: String = "",

    /** OIDC issuer URI (preferred). */
    var issuerUri: String = "",

    /** OIDC JWK set URI (alternative to issuer URI). */
    var jwkSetUri: String = "",
)

@Configuration
@EnableConfigurationProperties(EdgeAuthProperties::class)
class EdgeSecurityConfig {

    @Bean
    fun securityWebFilterChain(http: ServerHttpSecurity, props: EdgeAuthProperties): SecurityWebFilterChain {
        val mode = props.mode.trim().uppercase()

        // Keep actuator usable (health, prometheus scrape, etc.).
        http.authorizeExchange {
            it.pathMatchers("/actuator/**").permitAll()
        }

        if (mode == "DISABLED") {
            check(props.allowDisabled) {
                "edge.auth.mode=DISABLED is blocked unless edge.auth.allow-disabled=true"
            }

            http
                .csrf { it.disable() }
                .authorizeExchange { it.anyExchange().permitAll() }

            return http.build()
        }

        // Authenticated ingress.
        http
            .csrf { it.disable() }
            .authorizeExchange { it.anyExchange().authenticated() }
            .oauth2ResourceServer { rs ->
                rs.jwt { jwt ->
                    jwt.jwtDecoder(jwtDecoder(props))
                }
            }

        return http.build()
    }

    @Bean
    fun jwtDecoder(props: EdgeAuthProperties): ReactiveJwtDecoder {
        val mode = props.mode.trim().uppercase()
        return when (mode) {
            "HS256" -> {
                val secret = props.hs256Secret
                require(secret.isNotBlank()) { "edge.auth.hs256-secret must be set when edge.auth.mode=HS256" }

                val key = SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
                NimbusReactiveJwtDecoder
                    .withSecretKey(key)
                    .macAlgorithm(MacAlgorithm.HS256)
                    .build()
            }

            "OIDC" -> {
                val issuer = props.issuerUri.trim()
                val jwk = props.jwkSetUri.trim()

                when {
                    issuer.isNotBlank() -> ReactiveJwtDecoders.fromIssuerLocation(issuer)
                    jwk.isNotBlank() -> NimbusReactiveJwtDecoder.withJwkSetUri(jwk).build()
                    else -> error("edge.auth.issuer-uri or edge.auth.jwk-set-uri must be set when edge.auth.mode=OIDC")
                }
            }

            else -> error("edge.auth.mode must be DISABLED, HS256, or OIDC (was '${props.mode}')")
        }
    }
}
