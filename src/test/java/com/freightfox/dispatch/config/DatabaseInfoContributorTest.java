package com.freightfox.dispatch.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseInfoContributorTest {

    @Test
    void reportsTheDatabaseProductAndVersion() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(metaData.getDatabaseProductVersion()).thenReturn("16.4");

        Info.Builder builder = new Info.Builder();
        new DatabaseInfoContributor(dataSource).contribute(builder);

        assertThat(builder.build().getDetails()).containsEntry("database", Map.of("product", "PostgreSQL", "version", "16.4"));
    }

    @Test
    void reportsUnavailableWhenNoConnectionCanBeOpened() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("down"));

        Info.Builder builder = new Info.Builder();
        new DatabaseInfoContributor(dataSource).contribute(builder);

        assertThat(builder.build().getDetails()).containsEntry("database", Map.of("status", "unavailable"));
    }
}
