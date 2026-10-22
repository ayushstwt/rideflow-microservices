package com.ayshriv.rideservice.config;

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
    public OpenAPI rideServiceOpenApi(@Value("${app.version:unknown}") String version) {
        return new OpenAPI().info(new Info()
                .title("Ride Service")
                .description("Owns the ride lifecycle: request, status changes, cancel and complete.\nPublishes ride events to Kafka and applies the matching results; retries rides that find no driver.")
                .version(version)
                .license(new License().name("MIT").url("https://opensource.org/licenses/MIT"))
                .contact(new Contact().name("Rideflow team").email("rideflow@example.com")
                        .url("https://github.com/your-org/rideflow-microservices")));
    }
}
