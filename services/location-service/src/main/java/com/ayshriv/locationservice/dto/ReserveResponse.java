package com.ayshriv.locationservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReserveResponse {

    @Schema(description = "The driver that is now reserved for the ride", example = "driver-lko-001")
    private String driverId;
}
