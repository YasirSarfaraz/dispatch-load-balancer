package com.freightfox.dispatch.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Migrations that have been applied to the production database are immutable: Flyway checksums every
 * applied file (comments included) and refuses to start if one changed. This test fails the build first.
 * To change the schema, add a new migration (V2__...) and record its hash here once it is deployed.
 */
class AppliedMigrationsTest {

    /** SHA-256 of each migration applied in production (line endings normalised to LF). */
    private static final Map<String, String> APPLIED = Map.of(
            "V1__create_vehicles_and_delivery_orders.sql",
            "3477209e3d8bd819ed7c5d82a5f2e063a0696c11d071e502c07088b81fc9a8fb");

    @Test
    void appliedMigrationsAreUnchanged() throws IOException {
        Map<String, String> current = migrationHashes();

        APPLIED.forEach((file, hash) -> assertThat(current.get(file))
                .as("%s was applied in production and must never be edited; add a new migration instead", file)
                .isEqualTo(hash));
    }

    private static Map<String, String> migrationHashes() throws IOException {
        Map<String, String> hashes = new TreeMap<>();
        for (Resource migration : new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/*.sql")) {
            String content = migration.getContentAsString(StandardCharsets.UTF_8).replace("\r\n", "\n");
            hashes.put(migration.getFilename(), sha256(content));
        }
        return hashes;
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
