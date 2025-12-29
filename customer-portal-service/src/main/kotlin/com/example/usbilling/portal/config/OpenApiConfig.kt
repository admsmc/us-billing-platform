package com.example.usbilling.portal.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Contact
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun customerPortalOpenAPI(): OpenAPI {
        val securitySchemeName = "bearerAuth"

        return OpenAPI()
            .info(
                Info()
                    .title("Customer Portal API")
                    .version("1.0.0")
                    .description(
                        """
                        Customer-facing self-service REST API for utility billing platform.
                        
                        Provides endpoints for:
                        - Bill viewing and payment
                        - Usage analytics and insights
                        - Account management
                        - Service requests and outage reporting
                        - Document downloads
                        - Alert preferences
                        """.trimIndent(),
                    )
                    .contact(
                        Contact()
                            .name("Utility Billing Platform")
                            .email("support@example.com"),
                    )
                    .license(
                        License()
                            .name("Proprietary")
                            .url("https://example.com/license"),
                    ),
            )
            .addSecurityItem(SecurityRequirement().addList(securitySchemeName))
            .components(
                io.swagger.v3.oas.models.Components()
                    .addSecuritySchemes(
                        securitySchemeName,
                        SecurityScheme()
                            .name(securitySchemeName)
                            .type(SecurityScheme.Type.HTTP)
                            .scheme("bearer")
                            .bearerFormat("JWT")
                            .description("JWT authentication token"),
                    ),
            )
    }
}
