package com.freightfox.dispatch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.util.Objects;

/**
 * A delivery order. The primary key is the client supplied {@code orderId}.
 *
 * <p>Implements {@link Persistable} so Spring Data knows whether an entity with an assigned ID is
 * new. Without this, {@code saveAll} would call {@code merge} for every new entity and issue one
 * SELECT per row; with it, new rows go straight to {@code persist} and are JDBC-batched.
 */
@Entity
@Table(name = "delivery_orders")
public class DeliveryOrder implements Persistable<String> {

    public static final int MAX_ID_LENGTH = 64;
    public static final int MAX_ADDRESS_LENGTH = 500;

    @Id
    @Column(name = "order_id", length = MAX_ID_LENGTH, nullable = false, updatable = false)
    private String orderId;

    @Column(name = "latitude", nullable = false)
    private double latitude;

    @Column(name = "longitude", nullable = false)
    private double longitude;

    @Column(name = "address", length = MAX_ADDRESS_LENGTH, nullable = false)
    private String address;

    @Column(name = "package_weight", nullable = false)
    private int packageWeight;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", length = 10, nullable = false)
    private Priority priority;

    @Transient
    private boolean newEntity = true;

    protected DeliveryOrder() {
        // for JPA
    }

    public DeliveryOrder(String orderId, double latitude, double longitude, String address,
                         int packageWeight, Priority priority) {
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.latitude = latitude;
        this.longitude = longitude;
        this.address = address;
        this.packageWeight = packageWeight;
        this.priority = priority;
    }

    /** Copies every mutable field from {@code source} (used by upsert to update a managed entity). */
    public void updateFrom(DeliveryOrder source) {
        this.latitude = source.latitude;
        this.longitude = source.longitude;
        this.address = source.address;
        this.packageWeight = source.packageWeight;
        this.priority = source.priority;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.newEntity = false;
    }

    @Override
    public String getId() {
        return orderId;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    public String getOrderId() {
        return orderId;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public String getAddress() {
        return address;
    }

    public int getPackageWeight() {
        return packageWeight;
    }

    public Priority getPriority() {
        return priority;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof DeliveryOrder other && Objects.equals(orderId, other.orderId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(orderId);
    }

    @Override
    public String toString() {
        return "DeliveryOrder[" + orderId + ", " + priority + ", " + packageWeight + "]";
    }
}
