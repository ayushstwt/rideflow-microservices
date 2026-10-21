package com.ayshriv.matchingservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Mirrors the JSON returned by location-service's nearby-drivers endpoint. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NearbyDriverResponse {

    private String driverId;
    private double latitude;
    private double longitude;
    private double distanceInKm;
}
