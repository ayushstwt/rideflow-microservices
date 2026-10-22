package com.ayshriv.rideservice.dto;

import com.ayshriv.rideservice.model.RideStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RideResponse {

    @Schema(description = "Ride id (UUID)", example = "7c9e6679-7425-40de-944b-e07fc1f90ae7")
    private String id;
    @Schema(description = "Rider who requested the ride", example = "rider-lko-42")
    private String riderId;
    @Schema(description = "Assigned driver; null until a driver is matched", example = "driver-lko-001")
    private String driverId;

    @Schema(description = "Pickup latitude", example = "26.8467")
    private Double pickupLatitude;
    @Schema(description = "Pickup longitude", example = "80.9462")
    private Double pickupLongitude;
    @Schema(description = "Pickup address", example = "Hazratganj, Lucknow")
    private String pickupAddress;

    @Schema(description = "Drop-off latitude", example = "26.7606")
    private Double dropLatitude;
    @Schema(description = "Drop-off longitude", example = "80.8893")
    private Double dropLongitude;
    @Schema(description = "Drop-off address", example = "Chaudhary Charan Singh Airport, Lucknow")
    private String dropAddress;

    @Schema(description = "Current status. MATCHING until a driver is found; COMPLETED, CANCELLED and NO_DRIVER_FOUND are terminal",
            example = "MATCHING")
    private RideStatus status;
    @Schema(description = "Fare estimated at request time (50 + 12 per km, minimum 60.00)", example = "168.40")
    private BigDecimal estimatedFare;
    @Schema(description = "Final fare; null until the ride is completed", example = "168.40")
    private BigDecimal actualFare;

    @Schema(description = "When the ride was requested (UTC, ISO-8601)", example = "2026-01-01T10:00:00.123Z")
    private Instant createdAt;
    @Schema(description = "Last change (UTC, ISO-8601)", example = "2026-01-01T10:00:00.150Z")
    private Instant updatedAt;
    @Schema(description = "When the ride started; null before RIDE_STARTED", example = "2026-01-01T10:12:00Z")
    private Instant startedAt;
    @Schema(description = "When the ride completed; null before COMPLETED", example = "2026-01-01T10:35:00Z")
    private Instant completedAt;

    @Schema(description = "Optimistic-lock version; increases with every change", example = "1")
    private Long version;
}
