package com.ayshriv.locationservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NearbyDriverResponse {

    @Schema(description = "Id of the driver", example = "driver-lko-001")
    private String driverId;
    @Schema(description = "Driver's latitude", example = "26.8471")
    private double latitude;
    @Schema(description = "Driver's longitude", example = "80.9458")
    private double longitude;
    @Schema(description = "Straight-line distance from the search centre in kilometres", example = "0.52")
    private double distanceInKm;
}
