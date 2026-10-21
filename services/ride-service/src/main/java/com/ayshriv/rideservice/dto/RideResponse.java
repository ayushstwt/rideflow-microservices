package com.ayshriv.rideservice.dto;

import com.ayshriv.rideservice.model.RideStatus;
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

    private String id;
    private String riderId;
    private String driverId;

    private Double pickupLatitude;
    private Double pickupLongitude;
    private String pickupAddress;

    private Double dropLatitude;
    private Double dropLongitude;
    private String dropAddress;

    private RideStatus status;
    private BigDecimal estimatedFare;
    private BigDecimal actualFare;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant startedAt;
    private Instant completedAt;

    private Long version;
}
