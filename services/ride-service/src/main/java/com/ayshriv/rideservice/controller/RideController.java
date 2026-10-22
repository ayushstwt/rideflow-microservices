package com.ayshriv.rideservice.controller;

import com.ayshriv.rideservice.dto.RideRequest;
import com.ayshriv.rideservice.dto.RideResponse;
import com.ayshriv.rideservice.dto.RideStatusResponse;
import com.ayshriv.rideservice.exception.InvalidRideRequestException;
import com.ayshriv.rideservice.service.RideRequestResult;
import com.ayshriv.rideservice.service.RideService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/rides")
@RequiredArgsConstructor
public class RideController {

    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 64;

    private final RideService rideService;

    /** 201 for a new ride, 200 when the same rider repeats a request with an Idempotency-Key already used. */
    @PostMapping("/request")
    public ResponseEntity<RideResponse> requestRide(
            @Valid @RequestBody RideRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        RideRequestResult result = rideService.requestRide(request, normalizeKey(idempotencyKey));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.ride());
    }

    @GetMapping("/{rideId}")
    public ResponseEntity<RideResponse> getRide(@PathVariable String rideId) {
        return ResponseEntity.ok(rideService.getRideById(rideId));
    }

    @GetMapping("/{rideId}/status")
    public ResponseEntity<RideStatusResponse> getRideStatus(@PathVariable String rideId) {
        return ResponseEntity.ok(rideService.getRideStatus(rideId));
    }

    @GetMapping("/rider/{riderId}")
    public ResponseEntity<List<RideResponse>> getRidesByRider(@PathVariable String riderId) {
        return ResponseEntity.ok(rideService.getRidesByRider(riderId));
    }

    @PutMapping("/{rideId}/start")
    public ResponseEntity<RideResponse> startRide(@PathVariable String rideId) {
        return ResponseEntity.ok(rideService.startRide(rideId));
    }

    @PutMapping("/{rideId}/complete")
    public ResponseEntity<RideResponse> completeRide(@PathVariable String rideId) {
        return ResponseEntity.ok(rideService.completeRide(rideId));
    }

    @PutMapping("/{rideId}/cancel")
    public ResponseEntity<RideResponse> cancelRide(@PathVariable String rideId) {
        return ResponseEntity.ok(rideService.cancelRide(rideId));
    }

    /** A blank header counts as absent. */
    private static String normalizeKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new InvalidRideRequestException(
                    "Idempotency-Key must be at most " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
        return key;
    }
}
