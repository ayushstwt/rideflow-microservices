package com.ayshriv.matchingservice.client;

import com.ayshriv.matchingservice.dto.NearbyDriverResponse;
import com.ayshriv.matchingservice.dto.ReserveRequest;
import com.ayshriv.matchingservice.dto.ReserveResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Optional;

@FeignClient(name = "location-service", url = "${location-service.url}",
        fallback = LocationServiceClientFallback.class)
public interface LocationServiceClient {

    @GetMapping("/api/v1/locations/drivers/nearby")
    List<NearbyDriverResponse> findNearbyDrivers(@RequestParam("latitude") double latitude,
                                                 @RequestParam("longitude") double longitude,
                                                 @RequestParam("radius") double radius,
                                                 @RequestParam("limit") int limit);

    /**
     * Reserves the first still-free driver of the ordered candidates. Idempotent per ride: the same
     * ride always gets the same driver back. Empty when no candidate is free (409) or on fallback.
     */
    @PostMapping("/api/v1/locations/drivers/reserve")
    Optional<ReserveResponse> reserve(@RequestBody ReserveRequest request);
}
