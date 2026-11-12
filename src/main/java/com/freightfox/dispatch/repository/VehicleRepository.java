package com.freightfox.dispatch.repository;

import com.freightfox.dispatch.entity.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Spring Data repository for {@link Vehicle}, keyed by vehicleId. */
@Repository
public interface VehicleRepository extends JpaRepository<Vehicle, String> {
}
