package com.freightfox.dispatch.service.impl;

import com.freightfox.dispatch.config.DispatchProperties;
import com.freightfox.dispatch.exception.DuplicateIdException;
import com.freightfox.dispatch.service.UpsertResult;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Efficient, chunked upsert shared by the order and vehicle services (template method via
 * composition). For every chunk of {@code app.dispatch.upsert-chunk-size} rows it issues:
 * <ol>
 *   <li>ONE {@code findAllById} (a single {@code SELECT ... WHERE id IN (...)}),</li>
 *   <li>an in-memory merge: existing (managed) rows get {@code updateFrom(incoming)}, so Hibernate
 *       dirty checking emits JDBC-batched UPDATEs,</li>
 *   <li>ONE {@code saveAll(newRows)}: the entities implement {@link Persistable}, so new rows are
 *       {@code persist()}ed without a SELECT each and are JDBC-batched INSERTs,</li>
 *   <li>{@code flush()} + {@code clear()}, so the persistence context never holds more than one
 *       chunk, even for a 50,000-row request.</li>
 * </ol>
 * Callers must run inside a transaction (the services are {@code @Transactional}).
 */
@Component
public class ChunkedUpserter {

    private final EntityManager entityManager;
    private final int chunkSize;

    public ChunkedUpserter(EntityManager entityManager, DispatchProperties properties) {
        this.entityManager = entityManager;
        this.chunkSize = properties.upsertChunkSize();
    }

    /**
     * Describes what is being upserted.
     *
     * @param repository repository of the entity
     * @param updater    copies the mutable fields of the second argument (incoming) onto the first
     *                   (existing, managed)
     * @param idField    ID field name used in error messages, e.g. "orderId"
     * @param listField  request list name used in error messages, e.g. "orders"
     * @param <E>        entity type
     */
    public record Target<E extends Persistable<String>>(
            JpaRepository<E, String> repository,
            BiConsumer<E, E> updater,
            String idField,
            String listField) {

        public Target {
            Objects.requireNonNull(repository, "repository");
            Objects.requireNonNull(updater, "updater");
        }
    }

    /**
     * Inserts the new and updates the existing entities.
     *
     * @throws DuplicateIdException if the same ID occurs more than once (nothing is written)
     */
    public <E extends Persistable<String>> UpsertResult upsert(List<E> incoming, Target<E> target) {
        if (incoming == null || incoming.isEmpty()) {
            return new UpsertResult(0, 0);
        }
        rejectDuplicates(incoming, target);

        int inserted = 0;
        int updated = 0;
        for (int from = 0; from < incoming.size(); from += chunkSize) {
            List<E> chunk = incoming.subList(from, Math.min(incoming.size(), from + chunkSize));
            Map<String, E> existingById = loadExisting(chunk, target.repository());

            List<E> newEntities = new ArrayList<>(chunk.size() - existingById.size());
            for (E entity : chunk) {
                E existing = existingById.get(entity.getId());
                if (existing == null) {
                    newEntities.add(entity);
                } else {
                    target.updater().accept(existing, entity);
                }
            }
            if (!newEntities.isEmpty()) {
                target.repository().saveAll(newEntities);
            }
            entityManager.flush();
            entityManager.clear();

            inserted += newEntities.size();
            updated += chunk.size() - newEntities.size();
        }
        return new UpsertResult(inserted, updated);
    }

    private static <E extends Persistable<String>> Map<String, E> loadExisting(
            List<E> chunk, JpaRepository<E, String> repository) {
        List<String> ids = chunk.stream().map(Persistable::getId).toList();
        Map<String, E> byId = new HashMap<>();
        for (E entity : repository.findAllById(ids)) {
            byId.put(entity.getId(), entity);
        }
        return byId;
    }

    private static <E extends Persistable<String>> void rejectDuplicates(List<E> incoming, Target<E> target) {
        Set<String> seen = new HashSet<>(incoming.size() * 2);
        Set<String> duplicates = new LinkedHashSet<>();
        for (E entity : incoming) {
            if (!seen.add(entity.getId())) {
                duplicates.add(entity.getId());
            }
        }
        if (!duplicates.isEmpty()) {
            throw new DuplicateIdException(target.idField(), target.listField(), List.copyOf(duplicates));
        }
    }
}
