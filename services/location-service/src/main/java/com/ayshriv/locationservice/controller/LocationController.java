package com.ayshriv.locationservice.controller;

import com.ayshriv.locationservice.dto.DriverLocationRequest;
import com.ayshriv.locationservice.dto.NearbyDriverResponse;
import com.ayshriv.locationservice.dto.ReserveRequest;
import com.ayshriv.locationservice.dto.ReserveResponse;
import com.ayshriv.locationservice.exception.NoDriverAvailableException;
import com.ayshriv.locationservice.service.LocationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/locations")
@Validated
@RequiredArgsConstructor
@Tag(name = "Locations", description = "Driver positions, nearby search and driver reservation")
public class LocationController {

    /** Validation failures are a flat map of field (or parameter) name to message. */
    private static final String VALIDATION_ERROR_EXAMPLE = "{\"latitude\": \"latitude must be <= 90\"}";
    private static final String ERROR_500_EXAMPLE = "{\"error\": \"Internal server error\"}";

    private final LocationService locationService;

    @Operation(summary = "Update a driver's position",
            description = "Creates or refreshes the driver's position in the Redis GEO set and renews the heartbeat. "
                    + "A driver whose heartbeat is older than `location.driver-ttl-seconds` (default 30s) counts as offline, "
                    + "so drivers should call this every few seconds.")
    @ApiResponse(responseCode = "200", description = "Position stored",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object"),
                    examples = @ExampleObject(value = "{\"status\": \"updated\"}")))
    @ApiResponse(responseCode = "400", description = "Validation failed; body maps each invalid field to its message",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object"),
                    examples = @ExampleObject(value = VALIDATION_ERROR_EXAMPLE)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (no details are leaked)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object"),
                    examples = @ExampleObject(value = ERROR_500_EXAMPLE)))
    @PostMapping("/drivers/update")
    public ResponseEntity<Map<String, String>> updateDriverLocation(@Valid @RequestBody DriverLocationRequest request) {
        locationService.updateDriverLocation(request);
        return ResponseEntity.ok(Map.of("status", "updated"));
    }

    @Operation(summary = "Find nearby drivers",
            description = "Online drivers within `radius` km of the point, nearest first. Drivers with an expired heartbeat "
                    + "and drivers currently reserved for a ride are left out.")
    @ApiResponse(responseCode = "200", description = "Drivers found (empty list when none)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    array = @ArraySchema(schema = @Schema(implementation = NearbyDriverResponse.class))))
    @ApiResponse(responseCode = "400", description = "A parameter is missing, out of range or not a number; body maps the parameter to its message",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object"),
                    examples = @ExampleObject(value = "{\"radius\": \"must be less than or equal to 50.0\"}")))
    @ApiResponse(responseCode = "500", description = "Unexpected failure",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object"),
                    examples = @ExampleObject(value = ERROR_500_EXAMPLE)))
    @GetMapping("/drivers/nearby")
    public ResponseEntity<List<NearbyDriverResponse>> findNearbyDrivers(
            @Parameter(description = "Search centre latitude", example = "26.8467")
            @RequestParam @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
            @Parameter(description = "Search centre longitude", example = "80.9462")
            @RequestParam @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
            @Parameter(description = "Search radius in kilometres (0.1 - 50)", example = "5.0")
            @RequestParam(defaultValue = "5.0") @DecimalMin("0.1") @DecimalMax("50.0") Double radius,
            @Parameter(description = "Maximum number of drivers returned (1 - 50)", example = "10")
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) Integer limit) {
        return ResponseEntity.ok(locationService.findNearbyDrivers(latitude, longitude, radius, limit));
    }

    @Operation(summary = "Reserve a driver for a ride",
            description = "Reserves the first still-free driver of `candidateDriverIds` (ordered best first) for the ride. "
                    + "The check and the write happen in one atomic Redis script, so a driver is never reserved for two rides. "
                    + "Idempotent per ride: asking again for the same `rideId` returns the same driver. "
                    + "The reservation is released by the `ride.closed` event or `DELETE /reservations/{rideId}`, and expires on its own "
                    + "after `location.reservation-ttl-seconds` (default 1800).")
    @ApiResponse(responseCode = "200", description = "Driver reserved (or already reserved for this ride)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ReserveResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation failed (blank rideId, empty list, more than 20 candidates)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object"),
                    examples = @ExampleObject(value = "{\"candidateDriverIds\": \"candidateDriverIds must contain at most 20 drivers\"}")))
    @ApiResponse(responseCode = "409", description = "Every candidate is already reserved for another ride",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object"),
                    examples = @ExampleObject(value = "{\"error\": \"NO_DRIVER_AVAILABLE\"}")))
    @ApiResponse(responseCode = "500", description = "Unexpected failure",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object"),
                    examples = @ExampleObject(value = ERROR_500_EXAMPLE)))
    @PostMapping("/drivers/reserve")
    public ResponseEntity<ReserveResponse> reserveDriver(@Valid @RequestBody ReserveRequest request) {
        String driverId = locationService.reserveFirstAvailable(request.getRideId(), request.getCandidateDriverIds())
                .orElseThrow(() -> new NoDriverAvailableException(request.getRideId()));
        return ResponseEntity.ok(new ReserveResponse(driverId));
    }

    @Operation(summary = "Release the driver reserved for a ride",
            description = "Manual release for ops and testing; the normal path is the `ride.closed` event. "
                    + "Idempotent: an unknown or already released ride is not an error. "
                    + "A reservation that has meanwhile been taken over by another ride is never deleted.")
    @ApiResponse(responseCode = "204", description = "Released (or there was nothing to release)")
    @ApiResponse(responseCode = "500", description = "Unexpected failure",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object"),
                    examples = @ExampleObject(value = ERROR_500_EXAMPLE)))
    @DeleteMapping("/reservations/{rideId}")
    public ResponseEntity<Void> releaseReservation(
            @Parameter(description = "Ride whose driver should be freed", example = "7c9e6679-7425-40de-944b-e07fc1f90ae7")
            @PathVariable String rideId) {
        locationService.releaseDriver(rideId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Remove a driver",
            description = "Takes the driver out of the geo set and deletes the heartbeat, e.g. when the driver goes offline. "
                    + "Does not touch an existing ride reservation.")
    @ApiResponse(responseCode = "204", description = "Driver removed (also when the driver was unknown)")
    @ApiResponse(responseCode = "500", description = "Unexpected failure",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object"),
                    examples = @ExampleObject(value = ERROR_500_EXAMPLE)))
    @DeleteMapping("/drivers/{driverId}")
    public ResponseEntity<Void> removeDriver(
            @Parameter(description = "Driver id", example = "driver-lko-001") @PathVariable String driverId) {
        locationService.removeDriver(driverId);
        return ResponseEntity.noContent().build();
    }
}
