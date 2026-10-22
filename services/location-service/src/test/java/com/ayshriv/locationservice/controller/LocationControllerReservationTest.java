package com.ayshriv.locationservice.controller;

import com.ayshriv.locationservice.exception.GlobalExceptionHandler;
import com.ayshriv.locationservice.service.LocationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LocationController.class)
@Import(GlobalExceptionHandler.class)
class LocationControllerReservationTest {

    private static final String RESERVE = "/api/v1/locations/drivers/reserve";

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private LocationService locationService;

    @Test
    void reserve_returns200WithReservedDriver() throws Exception {
        when(locationService.reserveFirstAvailable("ride-1", List.of("d1", "d2"))).thenReturn(Optional.of("d2"));

        mockMvc.perform(post(RESERVE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rideId\":\"ride-1\",\"candidateDriverIds\":[\"d1\",\"d2\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driverId").value("d2"));
    }

    @Test
    void reserve_returns409WhenNoCandidateIsFree() throws Exception {
        when(locationService.reserveFirstAvailable(anyString(), anyList())).thenReturn(Optional.empty());

        mockMvc.perform(post(RESERVE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rideId\":\"ride-1\",\"candidateDriverIds\":[\"d1\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("NO_DRIVER_AVAILABLE"));
    }

    @Test
    void reserve_rejectsBlankRideIdEmptyListAndMoreThan20Candidates() throws Exception {
        mockMvc.perform(post(RESERVE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rideId\":\" \",\"candidateDriverIds\":[\"d1\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.rideId").exists());
        mockMvc.perform(post(RESERVE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rideId\":\"ride-1\",\"candidateDriverIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.candidateDriverIds").exists());

        String twentyOne = String.join(",", IntStream.rangeClosed(1, 21).mapToObj(i -> "\"d" + i + "\"").toList());
        mockMvc.perform(post(RESERVE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rideId\":\"ride-1\",\"candidateDriverIds\":[" + twentyOne + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.candidateDriverIds").exists());

        verify(locationService, never()).reserveFirstAvailable(any(), any());
    }

    @Test
    void release_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/locations/reservations/ride-1")).andExpect(status().isNoContent());

        verify(locationService).releaseDriver("ride-1");
    }
}
