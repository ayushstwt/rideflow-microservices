package com.ayshriv.locationservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
 * The generated OpenAPI document and Swagger UI of location-service. Redis and Kafka are never contacted:
 * connections are lazy and the Kafka listener and topic creation are switched off.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false"
})
@AutoConfigureMockMvc
class OpenApiDocsTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiDocsDescribeTheService() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value(startsWith("3.")))
                .andExpect(jsonPath("$.info.title").value("Location Service"))
                .andExpect(jsonPath("$.info.description").isNotEmpty())
                .andExpect(jsonPath("$.info.version").value(not(containsString("@"))))
                .andExpect(jsonPath("$.info.license.name").value("MIT"))
                .andExpect(jsonPath("$.info.contact.name").isNotEmpty());
    }

    @Test
    void apiDocsListEveryEndpointWithItsHttpMethod() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/locations/drivers/update'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/locations/drivers/nearby'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/locations/drivers/reserve'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/locations/reservations/{rideId}'].delete").exists())
                .andExpect(jsonPath("$.paths['/api/v1/locations/drivers/{driverId}'].delete").exists());
    }

    @Test
    void endpointsDocumentTheirRealStatusCodes() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/v1/locations/drivers/update'].post.responses.200").exists())
                .andExpect(jsonPath("$.paths['/api/v1/locations/drivers/update'].post.responses.400").exists())
                .andExpect(jsonPath("$.paths['/api/v1/locations/drivers/reserve'].post.responses.200").exists())
                .andExpect(jsonPath("$.paths['/api/v1/locations/drivers/reserve'].post.responses.409").exists())
                .andExpect(jsonPath("$.paths['/api/v1/locations/reservations/{rideId}'].delete.responses.204").exists())
                .andExpect(jsonPath("$.paths['/api/v1/locations/drivers/{driverId}'].delete.responses.204").exists())
                .andExpect(jsonPath("$.paths['/api/v1/locations/drivers/nearby'].get.responses.500").exists());
    }

    @Test
    void endpointsAreTaggedAndHaveSummaries() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/v1/locations/drivers/reserve'].post.tags[0]").value("Locations"))
                .andExpect(jsonPath("$.paths['/api/v1/locations/drivers/reserve'].post.summary").isNotEmpty())
                .andExpect(jsonPath("$.paths['/api/v1/locations/drivers/nearby'].get.description").isNotEmpty());
    }

    @Test
    void dtoFieldsCarryDescriptionsAndExamples() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.components.schemas.DriverLocationRequest.properties.latitude.example").value(26.8467))
                .andExpect(jsonPath("$.components.schemas.DriverLocationRequest.properties.longitude.example").value(80.9462))
                .andExpect(jsonPath("$.components.schemas.DriverLocationRequest.properties.driverId.description").isNotEmpty())
                .andExpect(jsonPath("$.components.schemas.ReserveRequest.properties.rideId.example").isNotEmpty())
                .andExpect(jsonPath("$.components.schemas.NearbyDriverResponse.properties.distanceInKm.description").isNotEmpty())
                .andExpect(jsonPath("$.components.schemas.ReserveResponse.properties.driverId.example").isNotEmpty());
    }

    @Test
    void nearbyQueryParametersAreDocumented() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/v1/locations/drivers/nearby'].get.parameters[*].name")
                        .value(hasItem("latitude")))
                .andExpect(jsonPath("$.paths['/api/v1/locations/drivers/nearby'].get.parameters[*].name")
                        .value(hasItem("radius")));
    }

    @Test
    void swaggerUiIsReachable() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/swagger-ui/index.html")));
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
