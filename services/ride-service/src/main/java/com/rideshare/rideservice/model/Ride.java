package com.rideshare.rideservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "rides", indexes = {
        @Index(name = "idx_rides_rider_id", columnList = "rider_id"),
        @Index(name = "idx_rides_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Ride {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 36, updatable = false)
    private String id;

    @Column(name = "rider_id", nullable = false)
    private String riderId;

    /** Null until a driver has been matched. */
    private String driverId;

    @Column(nullable = false)
    private Double pickupLatitude;
    @Column(nullable = false)
    private Double pickupLongitude;
    @Column(nullable = false)
    private String pickupAddress;

    @Column(nullable = false)
    private Double dropLatitude;
    @Column(nullable = false)
    private Double dropLongitude;
    @Column(nullable = false)
    private String dropAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RideStatus status;

    @Column(precision = 10, scale = 2)
    private BigDecimal estimatedFare;
    @Column(precision = 10, scale = 2)
    private BigDecimal actualFare;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    private Instant startedAt;
    private Instant completedAt;

    /** Optimistic locking: concurrent updates to the same ride fail instead of overwriting each other. */
    @Version
    private Long version;
}
