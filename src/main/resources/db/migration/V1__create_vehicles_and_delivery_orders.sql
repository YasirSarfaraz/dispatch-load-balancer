-- Initial schema. Applied by Flyway on start-up (PostgreSQL in production, locally and in the tests).
-- Hibernate only validates the schema (ddl-auto: validate); every later change is a new versioned migration.

CREATE TABLE vehicles (
    vehicle_id        VARCHAR(64)      NOT NULL,
    capacity          INTEGER          NOT NULL,
    current_latitude  DOUBLE PRECISION NOT NULL,
    current_longitude DOUBLE PRECISION NOT NULL,
    current_address   VARCHAR(500)     NOT NULL,
    CONSTRAINT pk_vehicles PRIMARY KEY (vehicle_id),
    CONSTRAINT ck_vehicles_capacity CHECK (capacity > 0),
    CONSTRAINT ck_vehicles_latitude CHECK (current_latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_vehicles_longitude CHECK (current_longitude BETWEEN -180 AND 180)
);

CREATE TABLE delivery_orders (
    order_id       VARCHAR(64)      NOT NULL,
    latitude       DOUBLE PRECISION NOT NULL,
    longitude      DOUBLE PRECISION NOT NULL,
    address        VARCHAR(500)     NOT NULL,
    package_weight INTEGER          NOT NULL,
    priority       VARCHAR(10)      NOT NULL,
    CONSTRAINT pk_delivery_orders PRIMARY KEY (order_id),
    CONSTRAINT ck_delivery_orders_weight CHECK (package_weight > 0),
    CONSTRAINT ck_delivery_orders_latitude CHECK (latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_delivery_orders_longitude CHECK (longitude BETWEEN -180 AND 180),
    CONSTRAINT ck_delivery_orders_priority CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW'))
);
