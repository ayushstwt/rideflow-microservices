package com.ayshriv.matchingservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Mirrors the JSON returned by location-service's reserve endpoint. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReserveResponse {

    private String driverId;
}
