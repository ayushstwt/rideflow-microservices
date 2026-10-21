package com.rideshare.rideservice.dto;

import com.rideshare.rideservice.model.Ride;
import org.springframework.stereotype.Component;

@Component
public class RideMapper {

    /** Maps only the request data; status and fare are decided by the service. */
    public Ride toEntity(RideRequest request) {
        return Ride.builder()
                .riderId(request.getRiderId())
                .pickupLatitude(request.getPickupLatitude())
                .pickupLongitude(request.getPickupLongitude())
                .pickupAddress(request.getPickupAddress())
                .dropLatitude(request.getDropLatitude())
                .dropLongitude(request.getDropLongitude())
                .dropAddress(request.getDropAddress())
                .build();
    }

    public RideResponse toResponse(Ride ride) {
        return RideResponse.builder()
                .id(ride.getId())
                .riderId(ride.getRiderId())
                .driverId(ride.getDriverId())
                .pickupLatitude(ride.getPickupLatitude())
                .pickupLongitude(ride.getPickupLongitude())
                .pickupAddress(ride.getPickupAddress())
                .dropLatitude(ride.getDropLatitude())
                .dropLongitude(ride.getDropLongitude())
                .dropAddress(ride.getDropAddress())
                .status(ride.getStatus())
                .estimatedFare(ride.getEstimatedFare())
                .actualFare(ride.getActualFare())
                .createdAt(ride.getCreatedAt())
                .updatedAt(ride.getUpdatedAt())
                .startedAt(ride.getStartedAt())
                .completedAt(ride.getCompletedAt())
                .version(ride.getVersion())
                .build();
    }
}
