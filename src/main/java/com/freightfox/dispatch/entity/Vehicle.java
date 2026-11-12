package com.freightfox.dispatch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.util.Objects;

/**
 * A vehicle of the fleet. The primary key is the client supplied {@code vehicleId}.
 * See {@link DeliveryOrder} for why this implements {@link Persistable}.
 */
@Entity
@Table(name = "vehicles")
public class Vehicle implements Persistable<String> {

    public static final int MAX_ID_LENGTH = 64;
    public static final int MAX_ADDRESS_LENGTH = 500;

    @Id
    @Column(name = "vehicle_id", length = MAX_ID_LENGTH, nullable = false, updatable = false)
    private String vehicleId;

    @Column(name = "capacity", nullable = false)
    private int capacity;

    @Column(name = "current_latitude", nullable = false)
    private double currentLatitude;

    @Column(name = "current_longitude", nullable = false)
    private double currentLongitude;

    @Column(name = "current_address", length = MAX_ADDRESS_LENGTH, nullable = false)
    private String currentAddress;

    @Transient
    private boolean newEntity = true;

    protected Vehicle() {
        // for JPA
    }

    public Vehicle(String vehicleId, int capacity, double currentLatitude, double currentLongitude,
                   String currentAddress) {
        this.vehicleId = Objects.requireNonNull(vehicleId, "vehicleId");
        this.capacity = capacity;
        this.currentLatitude = currentLatitude;
        this.currentLongitude = currentLongitude;
        this.currentAddress = currentAddress;
    }

    /** Copies every mutable field from {@code source} (used by upsert to update a managed entity). */
    public void updateFrom(Vehicle source) {
        this.capacity = source.capacity;
        this.currentLatitude = source.currentLatitude;
        this.currentLongitude = source.currentLongitude;
        this.currentAddress = source.currentAddress;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.newEntity = false;
    }

    @Override
    public String getId() {
        return vehicleId;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    public String getVehicleId() {
        return vehicleId;
    }

    public int getCapacity() {
        return capacity;
    }

    public double getCurrentLatitude() {
        return currentLatitude;
    }

    public double getCurrentLongitude() {
        return currentLongitude;
    }

    public String getCurrentAddress() {
        return currentAddress;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof Vehicle other && Objects.equals(vehicleId, other.vehicleId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(vehicleId);
    }

    @Override
    public String toString() {
        return "Vehicle[" + vehicleId + ", capacity=" + capacity + "]";
    }
}
