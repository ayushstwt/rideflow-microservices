package com.ayshriv.matchingservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Mirrors the body of location-service's reserve endpoint. Candidates are ordered best first. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReserveRequest {

    private String rideId;
    private List<String> candidateDriverIds;
}
