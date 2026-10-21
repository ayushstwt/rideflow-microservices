package com.ayshriv.matchingservice.service;

public interface DriverRatingProvider {

    /** Driver rating on a 0 - 5 scale. */
    double getRating(String driverId);
}
