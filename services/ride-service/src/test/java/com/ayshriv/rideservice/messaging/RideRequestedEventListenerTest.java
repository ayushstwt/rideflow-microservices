package com.ayshriv.rideservice.messaging;

import com.ayshriv.rideservice.model.Ride;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Verifies the publisher is only reached after commit, using a real (resourceless) transaction manager. */
class RideRequestedEventListenerTest {

    private AnnotationConfigApplicationContext context;
    private RideEventPublisher publisher;
    private TransactionTemplate template;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        publisher = context.getBean(RideEventPublisher.class);
        template = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void publishesOnlyAfterCommit() {
        Ride ride = Ride.builder().id("ride-1").build();

        template.executeWithoutResult(status -> {
            context.publishEvent(new RideRequestedInternalEvent(ride));
            verify(publisher, never()).publishRideRequested(any());
        });

        verify(publisher).publishRideRequested(ride);
    }

    @Test
    void neverPublishesOnRollback() {
        Ride ride = Ride.builder().id("ride-1").build();

        assertThatThrownBy(() -> template.executeWithoutResult(status -> {
            context.publishEvent(new RideRequestedInternalEvent(ride));
            throw new IllegalStateException("db failure");
        })).isInstanceOf(IllegalStateException.class);

        verify(publisher, never()).publishRideRequested(any());
    }

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {
        @Bean
        RideEventPublisher rideEventPublisher() {
            return Mockito.mock(RideEventPublisher.class);
        }

        @Bean
        RideRequestedEventListener rideRequestedEventListener(RideEventPublisher publisher) {
            return new RideRequestedEventListener(publisher);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return new NoOpTransactionManager();
        }
    }

    private static class NoOpTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
