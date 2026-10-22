package com.ayshriv.rideservice.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class RideStatusTest {

    @ParameterizedTest(name = "{0} -> {1} is allowed")
    @CsvSource({
            "REQUESTED, MATCHING",
            "REQUESTED, CANCELLED",
            "MATCHING, ACCEPTED",
            "MATCHING, NO_DRIVER_FOUND",
            "MATCHING, CANCELLED",
            "ACCEPTED, DRIVER_ARRIVING",
            "ACCEPTED, RIDE_STARTED",
            "ACCEPTED, CANCELLED",
            "DRIVER_ARRIVING, RIDE_STARTED",
            "DRIVER_ARRIVING, CANCELLED",
            "RIDE_STARTED, COMPLETED"
    })
    void allowedTransitions(RideStatus from, RideStatus to) {
        assertThat(from.canTransitionTo(to)).isTrue();
    }

    @ParameterizedTest(name = "{0} -> {1} is rejected")
    @CsvSource({
            "REQUESTED, ACCEPTED",
            "REQUESTED, COMPLETED",
            "MATCHING, RIDE_STARTED",
            "ACCEPTED, COMPLETED",
            "RIDE_STARTED, CANCELLED",
            "RIDE_STARTED, RIDE_STARTED",
            "COMPLETED, CANCELLED"
    })
    void disallowedTransitions(RideStatus from, RideStatus to) {
        assertThat(from.canTransitionTo(to)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = RideStatus.class, names = {"COMPLETED", "CANCELLED", "NO_DRIVER_FOUND"})
    void terminalStatesAllowNothing(RideStatus terminal) {
        for (RideStatus next : RideStatus.values()) {
            assertThat(terminal.canTransitionTo(next)).as("%s -> %s", terminal, next).isFalse();
        }
    }

    @Test
    void onlyCompletedCancelledAndNoDriverFoundAreTerminal() {
        for (RideStatus status : RideStatus.values()) {
            boolean expected = status == RideStatus.COMPLETED || status == RideStatus.CANCELLED
                    || status == RideStatus.NO_DRIVER_FOUND;
            assertThat(status.isTerminal()).as(status.name()).isEqualTo(expected);
        }
    }

    @Test
    void activeStatusesAreExactlyTheNonTerminalOnes() {
        for (RideStatus status : RideStatus.values()) {
            assertThat(RideStatus.ACTIVE_STATUSES.contains(status)).as(status.name()).isEqualTo(!status.isTerminal());
        }
    }

    @Test
    void noStatusCanTransitionBackToRequested() {
        for (RideStatus status : RideStatus.values()) {
            assertThat(status.canTransitionTo(RideStatus.REQUESTED)).isFalse();
        }
    }
}
