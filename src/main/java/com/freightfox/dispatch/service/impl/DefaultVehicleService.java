package com.freightfox.dispatch.service.impl;

import com.freightfox.dispatch.entity.Vehicle;
import com.freightfox.dispatch.repository.VehicleRepository;
import com.freightfox.dispatch.service.UpsertResult;
import com.freightfox.dispatch.service.VehicleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** {@link VehicleService} backed by JPA with a chunked, JDBC-batched upsert. */
@Service
public class DefaultVehicleService implements VehicleService {

    static final String ID_FIELD = "vehicleId";
    static final String LIST_FIELD = "vehicles";

    private static final Logger log = LoggerFactory.getLogger(DefaultVehicleService.class);
    private static final Sort BY_ID = Sort.by(ID_FIELD);

    private final VehicleRepository repository;
    private final ChunkedUpserter upserter;
    private final ChunkedUpserter.Target<Vehicle> target;

    public DefaultVehicleService(VehicleRepository repository, ChunkedUpserter upserter) {
        this.repository = repository;
        this.upserter = upserter;
        this.target = new ChunkedUpserter.Target<>(repository, Vehicle::updateFrom, ID_FIELD, LIST_FIELD);
    }

    @Override
    @Transactional
    public UpsertResult upsertVehicles(List<Vehicle> vehicles) {
        long start = System.nanoTime();
        UpsertResult result = upserter.upsert(vehicles, target);
        log.info("Upserted vehicles: {} inserted, {} updated in {} ms",
                result.inserted(), result.updated(), (System.nanoTime() - start) / 1_000_000);
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Vehicle> findAllVehicles() {
        return repository.findAll(BY_ID);
    }

    @Override
    @Transactional(readOnly = true)
    public long countVehicles() {
        return repository.count();
    }

    @Override
    @Transactional
    public void deleteAllVehicles() {
        repository.deleteAllInBatch();
        log.info("Deleted all vehicles");
    }
}
