package com.ayshriv.matchingservice.service;

/**
 * The driver reserved for a ride and how far away they are from the pickup.
 * <p>
 * Location and distance are null when the driver was reserved by an earlier delivery of the same ride
 * and no longer shows up in the nearby results (reserved drivers are hidden from them).
 */
public record Match(String driverId, Double latitude, Double longitude, Double distanceKm) {
}
