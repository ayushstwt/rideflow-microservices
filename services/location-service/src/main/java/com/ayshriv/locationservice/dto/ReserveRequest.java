package com.ayshriv.locationservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReserveRequest {

    @NotBlank(message = "rideId must not be blank")
    @Schema(description = "Ride the driver is reserved for", example = "7c9e6679-7425-40de-944b-e07fc1f90ae7")
    private String rideId;

    /** Best candidate first; the first one that is still free is reserved. */
    @NotEmpty(message = "candidateDriverIds must not be empty")
    @Size(max = 20, message = "candidateDriverIds must contain at most 20 drivers")
    @Schema(description = "Candidate driver ids, best first (1 to 20)",
            example = "[\"driver-lko-001\", \"driver-lko-002\", \"driver-lko-003\"]")
    private List<@NotBlank(message = "driver ids must not be blank") String> candidateDriverIds;
}
