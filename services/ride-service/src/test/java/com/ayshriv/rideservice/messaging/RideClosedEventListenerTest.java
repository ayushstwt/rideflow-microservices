package com.ayshriv.rideservice.messaging;

import com.ayshriv.events.RideClosedEvent;
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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Verifies ride.closed is only handed to Kafka after commit, using a real (resourceless) transaction manager. */
class RideClosedEventListenerTest {

    private final RideClosedEvent closed = RideClosedEvent.builder()
            .rideId("ride-1").riderId("rider-1").driverId("d1").finalStatus("COMPLETED").build();

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
        template.executeWithoutResult(status -> {
            context.publishEvent(new RideClosedInternalEvent(closed));
            verify(publisher, never()).publishRideClosed(any());
        });

        verify(publisher).publishRideClosed(closed);
    }

    @Test
    void neverPublishesOnRollback() {
        assertThatThrownBy(() -> template.executeWithoutResult(status -> {
            context.publishEvent(new RideClosedInternalEvent(closed));
            throw new IllegalStateException("db failure");
        })).isInstanceOf(IllegalStateException.class);

        verify(publisher, never()).publishRideClosed(any());
    }

    @Test
    void kafkaFailureAfterCommitDoesNotFailTheCommittedTransaction() {
        doThrow(new IllegalStateException("kafka down")).when(publisher).publishRideClosed(any());

        assertThatCode(() -> template.executeWithoutResult(
                status -> context.publishEvent(new RideClosedInternalEvent(closed)))).doesNotThrowAnyException();
    }

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {
        @Bean
        RideEventPublisher rideEventPublisher() {
            return Mockito.mock(RideEventPublisher.class);
        }

        @Bean
        RideClosedEventListener rideClosedEventListener(RideEventPublisher publisher) {
            return new RideClosedEventListener(publisher);
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
