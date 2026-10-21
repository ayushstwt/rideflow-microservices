package com.ayshriv.locationservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
