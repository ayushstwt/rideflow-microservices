package com.ayshriv.locationservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
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
    private String rideId;

    /** Best candidate first; the first one that is still free is reserved. */
    @NotEmpty(message = "candidateDriverIds must not be empty")
    @Size(max = 20, message = "candidateDriverIds must contain at most 20 drivers")
    private List<@NotBlank(message = "driver ids must not be blank") String> candidateDriverIds;
}
