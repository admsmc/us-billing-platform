package com.example.uspayroll.edge.security

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.web.SecurityFilterChain
import java.nio.charset.StandardCharsets
import javax.crypto.spec.SecretKeySpec

@ConfigurationProperties(prefix = "edge.auth")
data class EdgeAuthProperties(
    /**
     * Valid values:
     * - DISABLED: no auth (explicitly for local dev)
     * - HS256: validate JWTs signed with a shared HMAC secret
     */
    var mode: String = "DISABLED",
    var hs256Secret: String = "",
)

@Configuration
@EnableConfigurationProperties(EdgeAuthProperties::class)
class EdgeSecurityConfig {

    @Bean
    fun securityFilterChain(http: HttpSecurity, props: EdgeAuthProperties): SecurityFilterChain {
        val mode = props.mode.trim().uppercase()

        // Keep actuator usable locally.
        http.authorizeHttpRequests {
            it.requestMatchers("/actuator/**").permitAll()
        }

        if (mode == "DISABLED") {
            http
                .csrf { it.disable() }
                .authorizeHttpRequests { it.anyRequest().permitAll() }
            return http.build()
        }

        // Authenticated ingress.
        http
            .csrf { it.disable() }
            .authorizeHttpRequests { it.anyRequest().authenticated() }
            .oauth2ResourceServer { it.jwt(Customizer.withDefaults()) }

        return http.build()
    }

    @Bean
    fun jwtDecoder(props: EdgeAuthProperties): JwtDecoder {
        val mode = props.mode.trim().uppercase()
        if (mode != "HS256") {
            // If you set a different mode later (e.g. OIDC), replace this with issuer-uri/jwk-set-uri config.
            throw IllegalStateException("edge.auth.mode must be HS256 or DISABLED (was '${props.mode}')")
        }

        val secret = props.hs256Secret
        require(secret.isNotBlank()) { "edge.auth.hs256-secret must be set when edge.auth.mode=HS256" }

        val key = SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
        return NimbusJwtDecoder
            .withSecretKey(key)
            .macAlgorithm(MacAlgorithm.HS256)
            .build()
    }
}
