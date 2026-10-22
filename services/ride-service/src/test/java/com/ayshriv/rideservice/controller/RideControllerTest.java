package com.ayshriv.rideservice.controller;

import com.ayshriv.rideservice.dto.RideResponse;
import com.ayshriv.rideservice.dto.RideStatusResponse;
import com.ayshriv.rideservice.exception.ActiveRideExistsException;
import com.ayshriv.rideservice.exception.InvalidRideStateException;
import com.ayshriv.rideservice.exception.RideNotFoundException;
import com.ayshriv.rideservice.model.RideStatus;
import com.ayshriv.rideservice.service.RideRequestResult;
import com.ayshriv.rideservice.service.RideService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RideController.class)
class RideControllerTest {

    private static final String VALID_BODY = """
            {
              "riderId": "rider-1",
              "pickupLatitude": 12.97, "pickupLongitude": 77.59, "pickupAddress": "MG Road",
              "dropLatitude": 13.02, "dropLongitude": 77.65, "dropAddress": "Airport"
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RideService rideService;

    @Test
    void requestRide_returns201WithCreatedRide() throws Exception {
        when(rideService.requestRide(any(), any())).thenReturn(new RideRequestResult(RideResponse.builder()
                .id("ride-1")
                .riderId("rider-1")
                .status(RideStatus.MATCHING)
                .estimatedFare(new BigDecimal("150.00"))
                .updatedAt(Instant.parse("2026-01-01T10:00:00Z"))
                .build(), true));

        mockMvc.perform(post("/api/v1/rides/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("ride-1"))
                .andExpect(jsonPath("$.status").value("MATCHING"))
                .andExpect(jsonPath("$.estimatedFare").value(150.00))
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    void requestRide_passesIdempotencyKeyAndReturns200WhenReplayed() throws Exception {
        when(rideService.requestRide(any(), eq("key-1"))).thenReturn(new RideRequestResult(RideResponse.builder()
                .id("ride-1").riderId("rider-1").status(RideStatus.MATCHING).build(), false));

        mockMvc.perform(post("/api/v1/rides/request")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("ride-1"));
    }

    @Test
    void requestRide_blankIdempotencyKeyCountsAsAbsent() throws Exception {
        when(rideService.requestRide(any(), isNull())).thenReturn(new RideRequestResult(RideResponse.builder()
                .id("ride-1").riderId("rider-1").status(RideStatus.MATCHING).build(), true));

        mockMvc.perform(post("/api/v1/rides/request")
                        .header("Idempotency-Key", "  ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated());
    }

    @Test
    void requestRide_rejectsIdempotencyKeyLongerThan64Characters() throws Exception {
        mockMvc.perform(post("/api/v1/rides/request")
                        .header("Idempotency-Key", "k".repeat(65))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Idempotency-Key must be at most 64 characters"));

        verifyNoInteractions(rideService);
    }

    @Test
    void requestRide_accepts64CharacterIdempotencyKey() throws Exception {
        when(rideService.requestRide(any(), eq("k".repeat(64)))).thenReturn(new RideRequestResult(
                RideResponse.builder().id("ride-1").build(), true));

        mockMvc.perform(post("/api/v1/rides/request")
                        .header("Idempotency-Key", "k".repeat(64))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated());
    }

    @Test
    void requestRide_returns409WithExistingRideIdWhenRiderHasActiveRide() throws Exception {
        when(rideService.requestRide(any(), any())).thenThrow(new ActiveRideExistsException("rider-1", "ride-7"));

        mockMvc.perform(post("/api/v1/rides/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ACTIVE_RIDE_EXISTS"))
                .andExpect(jsonPath("$.rideId").value("ride-7"));
    }

    @Test
    void requestRide_returns400WithFieldMapWhenInvalid() throws Exception {
        String invalidBody = """
                {
                  "riderId": "",
                  "pickupLatitude": 95.0, "pickupLongitude": 77.59, "pickupAddress": "MG Road",
                  "dropLongitude": 77.65, "dropAddress": "Airport"
                }
                """;

        mockMvc.perform(post("/api/v1/rides/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.riderId").exists())
                .andExpect(jsonPath("$.pickupLatitude").exists())
                .andExpect(jsonPath("$.dropLatitude").exists());
    }

    @Test
    void getRide_returns404WhenMissing() throws Exception {
        when(rideService.getRideById("missing")).thenThrow(new RideNotFoundException("missing"));

        mockMvc.perform(get("/api/v1/rides/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Ride not found: missing"));
    }

    @Test
    void startRide_returns409WhenTransitionNotAllowed() throws Exception {
        when(rideService.startRide("r1")).thenThrow(
                new InvalidRideStateException("r1", RideStatus.CANCELLED, RideStatus.RIDE_STARTED));

        mockMvc.perform(put("/api/v1/rides/r1/start"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void getRideStatus_returnsOnlyIdStatusAndDriver() throws Exception {
        when(rideService.getRideStatus("r1")).thenReturn(new RideStatusResponse("r1", RideStatus.ACCEPTED, "d1"));

        mockMvc.perform(get("/api/v1/rides/r1/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rideId").value("r1"))
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.driverId").value("d1"))
                .andExpect(jsonPath("$.estimatedFare").doesNotExist());
    }

    @Test
    void getRideStatus_returns404WhenMissing() throws Exception {
        when(rideService.getRideStatus("missing")).thenThrow(new RideNotFoundException("missing"));

        mockMvc.perform(get("/api/v1/rides/missing/status")).andExpect(status().isNotFound());
    }

    @Test
    void unexpectedError_returns500WithoutDetails() throws Exception {
        when(rideService.cancelRide("r1")).thenThrow(new IllegalStateException("db password leaked"));

        mockMvc.perform(put("/api/v1/rides/r1/cancel"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Internal server error"));
    }
}
