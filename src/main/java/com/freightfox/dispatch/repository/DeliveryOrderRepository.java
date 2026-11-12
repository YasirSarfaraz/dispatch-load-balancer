package com.freightfox.dispatch.repository;

import com.freightfox.dispatch.entity.DeliveryOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Spring Data repository for {@link DeliveryOrder}, keyed by orderId. */
@Repository
public interface DeliveryOrderRepository extends JpaRepository<DeliveryOrder, String> {
}
