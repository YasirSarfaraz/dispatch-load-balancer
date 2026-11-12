package com.freightfox.dispatch.service.impl;

import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Priority;
import com.freightfox.dispatch.entity.Vehicle;
import com.freightfox.dispatch.service.OrderService;
import com.freightfox.dispatch.service.UpsertResult;
import com.freightfox.dispatch.service.VehicleService;
import com.freightfox.dispatch.service.impl.JdbcStatementRecorder.Call;
import com.freightfox.dispatch.service.impl.JdbcStatementRecorder.Kind;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that the upsert is efficient at the JDBC level (graded requirement): per chunk ONE
 * SELECT, and INSERTs/UPDATEs sent in JDBC batches of {@code hibernate.jdbc.batch_size} (50),
 * never row by row. Two independent sources of evidence: a JDBC proxy around the real DataSource
 * and Hibernate's own statistics.
 */
@SpringBootTest(properties = {
        // Own in-memory database so other test contexts cannot interfere.
        "spring.datasource.url=jdbc:h2:mem:upsert-batching;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=WARN"
})
@Import(UpsertJdbcBatchingIntegrationTest.CountingDataSourceConfig.class)
class UpsertJdbcBatchingIntegrationTest {

    private static final JdbcStatementRecorder RECORDER = new JdbcStatementRecorder();
    private static final int JDBC_BATCH_SIZE = 50;

    @TestConfiguration(proxyBeanMethods = false)
    static class CountingDataSourceConfig {

        /** Wraps the auto-configured DataSource so every statement Hibernate sends is counted. */
        @Bean
        static BeanPostProcessor countingDataSourcePostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof DataSource dataSource ? RECORDER.wrap(dataSource) : bean;
                }
            };
        }
    }

    @Autowired
    private OrderService orderService;
    @Autowired
    private VehicleService vehicleService;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;

    @BeforeEach
    void cleanDatabaseAndCounters() {
        orderService.deleteAllOrders();
        vehicleService.deleteAllVehicles();
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        resetCounters();
    }

    private void resetCounters() {
        RECORDER.reset();
        statistics.clear();
    }

    private static List<DeliveryOrder> orders(int count, int weight, Priority priority) {
        return IntStream.range(0, count)
                .mapToObj(i -> new DeliveryOrder(String.format("ORD%05d", i), 28.6 + i * 1e-4, 77.2,
                        "Address " + i, weight, priority))
                .toList();
    }

    @Test
    void newOrdersAreInsertedInJdbcBatchesAfterOneSelect() {
        UpsertResult result = orderService.upsertOrders(orders(120, 10, Priority.HIGH));

        assertThat(result).isEqualTo(new UpsertResult(120, 0));
        // One SELECT ... WHERE order_id IN (...) for the whole chunk, no SELECT per row.
        assertThat(RECORDER.count(Call.EXECUTE_QUERY, Kind.SELECT)).as(RECORDER.toString()).isEqualTo(1);
        // 120 rows go through addBatch and reach the database in 3 batches: 50 + 50 + 20.
        assertThat(RECORDER.count(Call.ADD_BATCH, Kind.INSERT)).isEqualTo(120);
        assertThat(RECORDER.count(Call.EXECUTE_BATCH, Kind.INSERT)).isEqualTo(3);
        assertThat(RECORDER.count(Call.EXECUTE_UPDATE, Kind.INSERT)).isZero();
        assertThat(RECORDER.roundTrips(Kind.UPDATE)).isZero();

        assertThat(statistics.getEntityInsertCount()).isEqualTo(120);
        assertThat(statistics.getEntityUpdateCount()).isZero();
        // 1 SELECT + 3 batch statements; row-by-row would need 121 statements.
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(4);
        assertThat(orderService.countOrders()).isEqualTo(120);
    }

    @Test
    void existingOrdersAreUpdatedInJdbcBatchesAfterOneSelect() {
        orderService.upsertOrders(orders(120, 10, Priority.HIGH));
        resetCounters();

        UpsertResult result = orderService.upsertOrders(orders(120, 25, Priority.LOW));

        assertThat(result).isEqualTo(new UpsertResult(0, 120));
        assertThat(RECORDER.count(Call.EXECUTE_QUERY, Kind.SELECT)).as(RECORDER.toString()).isEqualTo(1);
        assertThat(RECORDER.count(Call.ADD_BATCH, Kind.UPDATE)).isEqualTo(120);
        assertThat(RECORDER.count(Call.EXECUTE_BATCH, Kind.UPDATE)).isEqualTo(3);
        assertThat(RECORDER.count(Call.EXECUTE_UPDATE, Kind.UPDATE)).isZero();
        assertThat(RECORDER.roundTrips(Kind.INSERT)).isZero();
        assertThat(statistics.getEntityUpdateCount()).isEqualTo(120);
        assertThat(statistics.getEntityInsertCount()).isZero();

        assertThat(orderService.findAllOrders())
                .hasSize(120)
                .allSatisfy(order -> {
                    assertThat(order.getPackageWeight()).isEqualTo(25);
                    assertThat(order.getPriority()).isEqualTo(Priority.LOW);
                });
    }

    @Test
    void unchangedRowsAreNotWrittenAgain() {
        orderService.upsertOrders(orders(60, 10, Priority.HIGH));
        resetCounters();

        UpsertResult result = orderService.upsertOrders(orders(60, 10, Priority.HIGH));

        assertThat(result).isEqualTo(new UpsertResult(0, 60));
        assertThat(RECORDER.roundTrips(Kind.UPDATE)).isZero();
        assertThat(RECORDER.roundTrips(Kind.INSERT)).isZero();
    }

    @Test
    void largeMixedBatchUsesOneSelectPerChunkOfOneThousand() {
        orderService.upsertOrders(orders(500, 10, Priority.HIGH));
        resetCounters();

        // 2,500 rows -> chunks of 1000, 1000, 500. IDs 0..499 exist, 500..2499 are new.
        UpsertResult result = orderService.upsertOrders(orders(2500, 30, Priority.MEDIUM));

        assertThat(result).isEqualTo(new UpsertResult(2000, 500));
        assertThat(RECORDER.count(Call.EXECUTE_QUERY, Kind.SELECT)).as(RECORDER.toString()).isEqualTo(3);
        assertThat(RECORDER.count(Call.ADD_BATCH, Kind.INSERT)).isEqualTo(2000);
        assertThat(RECORDER.count(Call.EXECUTE_BATCH, Kind.INSERT)).isEqualTo(2000 / JDBC_BATCH_SIZE);
        assertThat(RECORDER.count(Call.ADD_BATCH, Kind.UPDATE)).isEqualTo(500);
        assertThat(RECORDER.count(Call.EXECUTE_BATCH, Kind.UPDATE)).isEqualTo(500 / JDBC_BATCH_SIZE);
        assertThat(RECORDER.count(Call.EXECUTE_UPDATE, Kind.INSERT)).isZero();
        assertThat(RECORDER.count(Call.EXECUTE_UPDATE, Kind.UPDATE)).isZero();
        assertThat(orderService.countOrders()).isEqualTo(2500);
    }

    @Test
    void vehiclesAreBatchedToo() {
        List<Vehicle> vehicles = IntStream.range(0, 120)
                .mapToObj(i -> new Vehicle(String.format("VEH%04d", i), 100 + i, 28.7, 77.1, "Depot " + i))
                .toList();

        assertThat(vehicleService.upsertVehicles(vehicles)).isEqualTo(new UpsertResult(120, 0));

        assertThat(RECORDER.count(Call.EXECUTE_QUERY, Kind.SELECT)).isEqualTo(1);
        assertThat(RECORDER.count(Call.EXECUTE_BATCH, Kind.INSERT)).isEqualTo(3);
        assertThat(RECORDER.count(Call.EXECUTE_UPDATE, Kind.INSERT)).isZero();
    }

    @Test
    void deleteAllIsASingleStatement() {
        orderService.upsertOrders(orders(120, 10, Priority.HIGH));
        resetCounters();

        orderService.deleteAllOrders();

        assertThat(RECORDER.roundTrips(Kind.DELETE)).as(RECORDER.toString()).isEqualTo(1);
        assertThat(RECORDER.roundTrips(Kind.SELECT)).isZero();
        assertThat(orderService.countOrders()).isZero();
    }
}
