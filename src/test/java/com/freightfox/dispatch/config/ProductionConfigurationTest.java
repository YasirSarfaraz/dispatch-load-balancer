package com.freightfox.dispatch.config;

import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds application.yml exactly as Spring Boot does at start-up, with the variables Render injects, so a
 * mistake in the production configuration fails the build instead of a deploy. Needs no database.
 */
class ProductionConfigurationTest {

    private static final Map<String, Object> RENDER_ENVIRONMENT = Map.of(
            "DB_HOST", "dpg-example-a", "DB_PORT", "5432", "DB_NAME", "dispatch",
            "DB_USERNAME", "dispatch", "DB_PASSWORD", "not-a-real-password");

    @Test
    void datasourceUrlIsBuiltFromRenderVariables() throws IOException {
        DataSourceProperties datasource = binder(RENDER_ENVIRONMENT)
                .bind("spring.datasource", Bindable.of(DataSourceProperties.class)).get();

        assertThat(datasource.getUrl())
                .isEqualTo("jdbc:postgresql://dpg-example-a:5432/dispatch?reWriteBatchedInserts=true");
        assertThat(datasource.getUsername()).isEqualTo("dispatch");
            }

    @Test
    void explicitDbUrlOverridesTheHostVariables() throws IOException {
        Map<String, Object> environment = new java.util.HashMap<>(RENDER_ENVIRONMENT);
        environment.put("DB_URL", "jdbc:postgresql://elsewhere:6543/other");

        DataSourceProperties datasource = binder(environment)
                .bind("spring.datasource", Bindable.of(DataSourceProperties.class)).get();

        assertThat(datasource.getUrl()).isEqualTo("jdbc:postgresql://elsewhere:6543/other");
    }

    @Test
    void withoutRenderVariablesItConnectsToTheLocalComposeDatabase() throws IOException {
        DataSourceProperties datasource = binder(Map.of())
                .bind("spring.datasource", Bindable.of(DataSourceProperties.class)).get();

        assertThat(datasource.getUrl()).isEqualTo("jdbc:postgresql://localhost:5432/dispatch?reWriteBatchedInserts=true");
        assertThat(datasource.getUsername()).isEqualTo("dispatch");
    }

    @Test
    void hikariSettingsBindToTheConnectionPool() throws IOException {
        HikariConfig hikari = binder(RENDER_ENVIRONMENT)
                .bind("spring.datasource.hikari", Bindable.of(HikariConfig.class)).get();

        assertThat(hikari.getMaximumPoolSize()).isEqualTo(5);
        assertThat(hikari.getMinimumIdle()).isEqualTo(1);
        assertThat(hikari.getConnectionTimeout()).isEqualTo(10_000);
        assertThat(hikari.getMaxLifetime()).isEqualTo(900_000);
    }

    @Test
    void hibernateOnlyValidatesTheFlywaySchema() throws IOException {
        String ddlAuto = binder(RENDER_ENVIRONMENT).bind("spring.jpa.hibernate.ddl-auto", String.class).get();

        assertThat(ddlAuto).isEqualTo("validate");
    }

    private static Binder binder(Map<String, Object> environment) throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("render-environment", environment));
        for (PropertySource<?> yaml : new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))) {
            sources.addLast(yaml);
        }
        return new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources),
                ApplicationConversionService.getSharedInstance());
    }
}
