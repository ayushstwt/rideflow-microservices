package com.ayshriv.rideservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The generated OpenAPI document and Swagger UI of ride-service. Uses H2 (test profile); Kafka is never contacted
 * because the listeners and topic creation are off, and the retry scheduler is pushed far into the future.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "spring.datasource.url=jdbc:h2:mem:ride_docs;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "ride.matching.scheduler-interval-seconds=3600"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiDocsTest {

    private static final String REQUEST = "$.paths['/api/v1/rides/request'].post";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiDocsDescribeTheService() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value(startsWith("3.")))
                .andExpect(jsonPath("$.info.title").value("Ride Service"))
                .andExpect(jsonPath("$.info.description").isNotEmpty())
                .andExpect(jsonPath("$.info.version").value(not(containsString("@"))))
                .andExpect(jsonPath("$.info.license.name").value("MIT"))
                .andExpect(jsonPath("$.info.contact.name").isNotEmpty());
    }

    @Test
    void apiDocsListEveryEndpointWithItsHttpMethod() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(REQUEST).exists())
                .andExpect(jsonPath("$.paths['/api/v1/rides/{rideId}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/rides/{rideId}/status'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/rides/rider/{riderId}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/rides/{rideId}/start'].put").exists())
                .andExpect(jsonPath("$.paths['/api/v1/rides/{rideId}/complete'].put").exists())
                .andExpect(jsonPath("$.paths['/api/v1/rides/{rideId}/cancel'].put").exists());
    }

    @Test
    void requestRideDocumentsTheIdempotencyKeyHeaderAnd200Versus201() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath(REQUEST + ".parameters[?(@.name=='Idempotency-Key')].in").value(hasItem("header")))
                .andExpect(jsonPath(REQUEST + ".parameters[?(@.name=='Idempotency-Key')].schema.maxLength").value(hasItem(64)))
                .andExpect(jsonPath(REQUEST + ".responses.201").exists())
                .andExpect(jsonPath(REQUEST + ".responses.200.description").value(containsString("Idempotency-Key")))
                .andExpect(jsonPath(REQUEST + ".responses.400").exists())
                .andExpect(jsonPath(REQUEST + ".responses.409").exists())
                .andExpect(jsonPath(REQUEST + ".responses.500").exists())
                .andExpect(jsonPath(REQUEST + ".description").value(containsString("201 vs 200")));
    }

    @Test
    void stateChangingEndpointsDocumentTheLifecycleAndTheirErrors() throws Exception {
        for (String action : new String[]{"start", "complete", "cancel"}) {
            String operation = "$.paths['/api/v1/rides/{rideId}/" + action + "'].put";
            mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(jsonPath(operation + ".description").value(containsString("Status lifecycle")))
                    .andExpect(jsonPath(operation + ".responses.200").exists())
                    .andExpect(jsonPath(operation + ".responses.404").exists())
                    .andExpect(jsonPath(operation + ".responses.409").exists());
        }
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/v1/rides/{rideId}/status'].get.description")
                        .value(containsString("Status lifecycle")))
                .andExpect(jsonPath("$.paths['/api/v1/rides/{rideId}'].get.responses.404").exists())
                .andExpect(jsonPath("$.paths['/api/v1/rides/rider/{riderId}'].get.responses.200").exists());
    }

    @Test
    void dtoFieldsCarryDescriptionsAndExamples() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.components.schemas.RideRequest.properties.pickupLatitude.example").value(26.8467))
                .andExpect(jsonPath("$.components.schemas.RideRequest.properties.pickupLongitude.example").value(80.9462))
                .andExpect(jsonPath("$.components.schemas.RideRequest.properties.riderId.description").isNotEmpty())
                .andExpect(jsonPath("$.components.schemas.RideResponse.properties.id.example")
                        .value("7c9e6679-7425-40de-944b-e07fc1f90ae7"))
                .andExpect(jsonPath("$.components.schemas.RideResponse.properties.status.description").isNotEmpty())
                .andExpect(jsonPath("$.components.schemas.RideResponse.properties.status.enum").isArray())
                .andExpect(jsonPath("$.components.schemas.RideStatusResponse.properties.driverId.description").isNotEmpty());
    }

    @Test
    void swaggerUiIsReachable() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/swagger-ui/index.html")));
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
