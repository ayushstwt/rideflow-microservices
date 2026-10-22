package com.ayshriv.rideservice.controller;

import com.ayshriv.rideservice.dto.RideRequest;
import com.ayshriv.rideservice.dto.RideResponse;
import com.ayshriv.rideservice.dto.RideStatusResponse;
import com.ayshriv.rideservice.exception.InvalidRideRequestException;
import com.ayshriv.rideservice.service.RideRequestResult;
import com.ayshriv.rideservice.service.RideService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
@Tag(name = "Rides", description = "Request a ride, follow its status, start, complete or cancel it")
public class RideController {

    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 64;

    private static final String LIFECYCLE = "**Status lifecycle:** REQUESTED -> MATCHING -> ACCEPTED -> DRIVER_ARRIVING -> "
            + "RIDE_STARTED -> COMPLETED. A ride can be CANCELLED from REQUESTED, MATCHING, ACCEPTED or DRIVER_ARRIVING. "
            + "MATCHING becomes NO_DRIVER_FOUND once every match attempt failed. "
            + "COMPLETED, CANCELLED and NO_DRIVER_FOUND are terminal; any other move is rejected with 409.";

    /** Most errors are {"error": "<message>"}; validation failures are a flat field -> message map. */
    private static final String ERROR_500_EXAMPLE = "{\"error\": \"Internal server error\"}";
    private static final String NOT_FOUND_EXAMPLE = "{\"error\": \"Ride not found: 7c9e6679-7425-40de-944b-e07fc1f90ae7\"}";
    private static final String BAD_TRANSITION_EXAMPLE =
            "{\"error\": \"Ride 7c9e6679-7425-40de-944b-e07fc1f90ae7 cannot move from COMPLETED to CANCELLED\"}";

    private final RideService rideService;

    @Operation(summary = "Request a ride",
            description = "Creates the ride as REQUESTED and moves it to MATCHING in the same transaction; matching-service then "
                    + "looks for a driver asynchronously (poll `GET /{rideId}/status`).\n\n"
                    + "**201 vs 200:** the first request answers `201 Created`. Repeating it with the same `Idempotency-Key` "
                    + "(same `riderId`) returns the same ride with `200 OK` instead of creating a second one, so a client may "
                    + "safely retry after a timeout.\n\n"
                    + "**One active ride per rider:** while the rider has a ride in REQUESTED, MATCHING, ACCEPTED, DRIVER_ARRIVING "
                    + "or RIDE_STARTED, a new request answers `409` with the id of that ride (a replay with a known "
                    + "`Idempotency-Key` skips this check).\n\n" + LIFECYCLE)
    @ApiResponse(responseCode = "201", description = "Ride created and MATCHING",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = RideResponse.class)))
    @ApiResponse(responseCode = "200", description = "Replay: a ride with this Idempotency-Key already exists and is returned unchanged",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = RideResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation failed (body maps each invalid field to its message), or "
            + "`{\"error\": ...}` for identical pickup and drop, a malformed body or an Idempotency-Key over 64 characters",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object"),
                    examples = @ExampleObject(value = "{\"riderId\": \"riderId must not be blank\", \"pickupLatitude\": \"pickupLatitude must be <= 90\"}")))
    @ApiResponse(responseCode = "409", description = "The rider already has an active ride; `rideId` is that ride",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object"),
                    examples = @ExampleObject(value = "{\"error\": \"ACTIVE_RIDE_EXISTS\", \"message\": \"Rider rider-lko-42 already has an active ride: "
                            + "7c9e6679-7425-40de-944b-e07fc1f90ae7\", \"rideId\": \"7c9e6679-7425-40de-944b-e07fc1f90ae7\"}")))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (no details are leaked)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object"),
                    examples = @ExampleObject(value = ERROR_500_EXAMPLE)))
    @PostMapping("/request")
    public ResponseEntity<RideResponse> requestRide(
            @Valid @RequestBody RideRequest request,
            @Parameter(name = "Idempotency-Key", in = io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER, required = false,
                    description = "Optional client-chosen key (max 64 characters, e.g. a UUID), unique per rider. "
                            + "Same key again: `200 OK` with the original ride instead of a new `201`. A blank value counts as absent.",
                    schema = @Schema(type = "string", maxLength = 64), example = "5f0c8e1a-2b7d-4c1e-9a3f-1d2e3f4a5b6c")
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        RideRequestResult result = rideService.requestRide(request, normalizeKey(idempotencyKey));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.ride());
    }

    @Operation(summary = "Get a ride", description = "Full details of one ride. " + LIFECYCLE)
    @ApiResponse(responseCode = "200", description = "The ride",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = RideResponse.class)))
    @ApiResponse(responseCode = "404", description = "Unknown ride id",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object"),
                    examples = @ExampleObject(value = NOT_FOUND_EXAMPLE)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object"),
                    examples = @ExampleObject(value = ERROR_500_EXAMPLE)))
    @GetMapping("/{rideId}")
    public ResponseEntity<RideResponse> getRide(
            @Parameter(description = "Ride id (UUID)", example = "7c9e6679-7425-40de-944b-e07fc1f90ae7") @PathVariable String rideId) {
        return ResponseEntity.ok(rideService.getRideById(rideId));
    }

    @Operation(summary = "Get a ride's status",
            description = "Lightweight view for apps that poll: only the id, the status and the assigned driver "
                    + "(`null` until a driver is matched). Poll until the status is ACCEPTED, NO_DRIVER_FOUND or CANCELLED. " + LIFECYCLE)
    @ApiResponse(responseCode = "200", description = "Current status",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = RideStatusResponse.class)))
    @ApiResponse(responseCode = "404", description = "Unknown ride id",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object"),
                    examples = @ExampleObject(value = NOT_FOUND_EXAMPLE)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object"),
                    examples = @ExampleObject(value = ERROR_500_EXAMPLE)))
    @GetMapping("/{rideId}/status")
    public ResponseEntity<RideStatusResponse> getRideStatus(
            @Parameter(description = "Ride id (UUID)", example = "7c9e6679-7425-40de-944b-e07fc1f90ae7") @PathVariable String rideId) {
        return ResponseEntity.ok(rideService.getRideStatus(rideId));
    }

    @Operation(summary = "List a rider's rides", description = "All rides of one rider, newest first. Empty list when there are none.")
    @ApiResponse(responseCode = "200", description = "The rider's rides",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    array = @ArraySchema(schema = @Schema(implementation = RideResponse.class))))
    @ApiResponse(responseCode = "500", description = "Unexpected failure",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object"),
                    examples = @ExampleObject(value = ERROR_500_EXAMPLE)))
    @GetMapping("/rider/{riderId}")
    public ResponseEntity<List<RideResponse>> getRidesByRider(
            @Parameter(description = "Rider id", example = "rider-lko-42") @PathVariable String riderId) {
        return ResponseEntity.ok(rideService.getRidesByRider(riderId));
    }

    @Operation(summary = "Start a ride",
            description = "The driver picked the rider up. Allowed from ACCEPTED or DRIVER_ARRIVING and only with an assigned driver; "
                    + "sets `startedAt`. " + LIFECYCLE)
    @ApiResponse(responseCode = "200", description = "Ride is now RIDE_STARTED",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = RideResponse.class)))
    @ApiResponse(responseCode = "404", description = "Unknown ride id",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object"),
                    examples = @ExampleObject(value = NOT_FOUND_EXAMPLE)))
    @ApiResponse(responseCode = "409", description = "Move not allowed from the current status, no driver assigned, or the ride was "
            + "modified concurrently (retry)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object"),
                    examples = @ExampleObject(value = "{\"error\": \"Ride 7c9e6679-7425-40de-944b-e07fc1f90ae7 cannot move from MATCHING to RIDE_STARTED\"}")))
    @ApiResponse(responseCode = "500", description = "Unexpected failure",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object"),
                    examples = @ExampleObject(value = ERROR_500_EXAMPLE)))
    @PutMapping("/{rideId}/start")
    public ResponseEntity<RideResponse> startRide(
            @Parameter(description = "Ride id (UUID)", example = "7c9e6679-7425-40de-944b-e07fc1f90ae7") @PathVariable String rideId) {
        return ResponseEntity.ok(rideService.startRide(rideId));
    }

    @Operation(summary = "Complete a ride",
            description = "Allowed from RIDE_STARTED only. Sets `completedAt` and `actualFare` (equal to `estimatedFare`) and publishes "
                    + "`ride.closed`, which releases the driver. " + LIFECYCLE)
    @ApiResponse(responseCode = "200", description = "Ride is now COMPLETED",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = RideResponse.class)))
    @ApiResponse(responseCode = "404", description = "Unknown ride id",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object"),
                    examples = @ExampleObject(value = NOT_FOUND_EXAMPLE)))
    @ApiResponse(responseCode = "409", description = "Move not allowed from the current status, or the ride was modified concurrently (retry)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object"),
                    examples = @ExampleObject(value = "{\"error\": \"Ride 7c9e6679-7425-40de-944b-e07fc1f90ae7 cannot move from ACCEPTED to COMPLETED\"}")))
    @ApiResponse(responseCode = "500", description = "Unexpected failure",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object"),
                    examples = @ExampleObject(value = ERROR_500_EXAMPLE)))
    @PutMapping("/{rideId}/complete")
    public ResponseEntity<RideResponse> completeRide(
            @Parameter(description = "Ride id (UUID)", example = "7c9e6679-7425-40de-944b-e07fc1f90ae7") @PathVariable String rideId) {
        return ResponseEntity.ok(rideService.completeRide(rideId));
    }

    @Operation(summary = "Cancel a ride",
            description = "Allowed from REQUESTED, MATCHING, ACCEPTED and DRIVER_ARRIVING. Publishes `ride.closed`, which releases the "
                    + "driver reserved for the ride. " + LIFECYCLE)
    @ApiResponse(responseCode = "200", description = "Ride is now CANCELLED",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = RideResponse.class)))
    @ApiResponse(responseCode = "404", description = "Unknown ride id",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object"),
                    examples = @ExampleObject(value = NOT_FOUND_EXAMPLE)))
    @ApiResponse(responseCode = "409", description = "Move not allowed from the current status (e.g. already finished or started), or the "
            + "ride was modified concurrently (retry)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object"),
                    examples = @ExampleObject(value = BAD_TRANSITION_EXAMPLE)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object"),
                    examples = @ExampleObject(value = ERROR_500_EXAMPLE)))
    @PutMapping("/{rideId}/cancel")
    public ResponseEntity<RideResponse> cancelRide(
            @Parameter(description = "Ride id (UUID)", example = "7c9e6679-7425-40de-944b-e07fc1f90ae7") @PathVariable String rideId) {
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
