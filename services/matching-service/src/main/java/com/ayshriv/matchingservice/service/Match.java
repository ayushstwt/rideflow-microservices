package com.ayshriv.matchingservice.service;

/** The driver chosen for a ride and how far away they are from the pickup. */
public record Match(String driverId, double latitude, double longitude, double distanceKm) {
}
