package com.freightfox.dispatch.config;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Adds the database in use to {@code /actuator/info}, e.g. {@code "database": {"product": "PostgreSQL",
 * "version": "16.4"}}, so anyone can confirm which database a deployment runs on.
 */
@Component
public class DatabaseInfoContributor implements InfoContributor {

    private final DataSource dataSource;

    public DatabaseInfoContributor(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void contribute(Info.Builder builder) {
        Map<String, Object> database = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            database.put("product", metaData.getDatabaseProductName());
            database.put("version", metaData.getDatabaseProductVersion());
        } catch (SQLException e) {
            database.put("status", "unavailable");
        }
        builder.withDetail("database", database);
    }
}
