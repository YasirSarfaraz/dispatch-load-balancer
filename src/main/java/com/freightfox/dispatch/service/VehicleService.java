package com.freightfox.dispatch.service;

import com.freightfox.dispatch.entity.Vehicle;

import java.util.List;

/** Persistence use cases for vehicles. */
public interface VehicleService {

    /**
     * Inserts new vehicles and updates existing ones (matched by vehicleId), in chunks.
     *
     * @throws com.freightfox.dispatch.exception.BadRequestException if the batch contains
     *         duplicate vehicleIds
     */
    UpsertResult upsertVehicles(List<Vehicle> vehicles);

    /** All vehicles, sorted by vehicleId. */
    List<Vehicle> findAllVehicles();

    long countVehicles();

    void deleteAllVehicles();
}
