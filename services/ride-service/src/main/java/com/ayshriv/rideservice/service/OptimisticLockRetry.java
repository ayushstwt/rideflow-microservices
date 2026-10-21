package com.ayshriv.rideservice.service;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * Retries an operation that lost an optimistic-lock race (the {@code @Version} field on Ride).
 * <p>
 * It must wrap the call to the transactional service method from the outside: the failed attempt's
 * transaction is rolled back, and each retry starts a fresh transaction that re-reads the ride, so a
 * concurrent cancel or assignment is seen and the idempotency check in the service decides what to do.
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class OptimisticLockRetry {

    static final int MAX_ATTEMPTS = 3;

    /** Runs the action up to {@value #MAX_ATTEMPTS} times; rethrows the last failure if all attempts lose. */
    public static void run(String description, Runnable action) {
        for (int attempt = 1; ; attempt++) {
            try {
                action.run();
                return;
            } catch (OptimisticLockingFailureException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    log.error("{}: giving up after {} optimistic-lock conflicts", description, attempt, e);
                    throw e;
                }
                log.warn("{}: optimistic-lock conflict on attempt {}/{}, retrying", description, attempt, MAX_ATTEMPTS);
            }
        }
    }
}
