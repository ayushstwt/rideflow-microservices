package com.ayshriv.matchingservice.service;

import com.ayshriv.matchingservice.dto.NearbyDriverResponse;

public record ScoredDriver(NearbyDriverResponse driver, double score) {
}
