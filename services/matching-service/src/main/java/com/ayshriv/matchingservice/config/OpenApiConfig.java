package com.ayshriv.matchingservice.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Metadata shown at the top of /swagger-ui.html and in /v3/api-docs. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI matchingServiceOpenApi(@Value("${app.version:unknown}") String version) {
        return new OpenAPI().info(new Info()
                .title("Matching Service")
                .description("Consumes ride requests from Kafka, ranks nearby drivers and reserves the best free one.\nEvent-driven: it has no business REST API, only the actuator health and info endpoints.")
                .version(version)
                .license(new License().name("MIT").url("https://opensource.org/licenses/MIT"))
                .contact(new Contact().name("Rideflow team").email("rideflow@example.com")
                        .url("https://github.com/your-org/rideflow-microservices")));
    }
}
