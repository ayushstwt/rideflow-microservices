package com.ayshriv.matchingservice.client;

import com.ayshriv.matchingservice.dto.NearbyDriverResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name = "location-service", url = "${location-service.url}",
        fallback = LocationServiceClientFallback.class)
public interface LocationServiceClient {

    @GetMapping("/api/v1/locations/drivers/nearby")
    List<NearbyDriverResponse> findNearbyDrivers(@RequestParam("latitude") double latitude,
                                                 @RequestParam("longitude") double longitude,
                                                 @RequestParam("radius") double radius,
                                                 @RequestParam("limit") int limit);
}
