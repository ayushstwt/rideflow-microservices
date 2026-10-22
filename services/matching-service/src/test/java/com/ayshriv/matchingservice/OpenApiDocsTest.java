package com.ayshriv.matchingservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * matching-service has no business REST API: its docs only list the actuator health and info endpoints.
 * Kafka is never contacted (listener and topic creation are off).
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
                .andExpect(jsonPath("$.info.title").value("Matching Service"))
                .andExpect(jsonPath("$.info.description").isNotEmpty())
                .andExpect(jsonPath("$.info.version").value(not(containsString("@"))))
                .andExpect(jsonPath("$.info.license.name").value("MIT"))
                .andExpect(jsonPath("$.info.contact.name").isNotEmpty());
    }

    @Test
    void apiDocsListTheActuatorHealthAndInfoEndpointsOnly() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/actuator/health'].get").exists())
                .andExpect(jsonPath("$.paths['/actuator/info'].get").exists())
                // No business endpoint: nothing under /api/ is documented.
                .andExpect(content().string(not(containsString("/api/"))));
    }

    @Test
    void swaggerUiIsReachable() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/swagger-ui/index.html")));
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
