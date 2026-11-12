package com.freightfox.dispatch.exception;

import java.util.List;

/** A batch contains the same ID more than once (HTTP 400). */
public class DuplicateIdException extends BadRequestException {

    /**
     * @param idField      name of the ID field, e.g. "orderId"
     * @param listField    name of the list in the request, e.g. "orders"
     * @param duplicateIds the duplicated IDs, each listed once, in request order
     */
    public DuplicateIdException(String idField, String listField, List<String> duplicateIds) {
        super(String.format(ErrorMessages.DUPLICATE_IDS, idField), details(idField, listField, duplicateIds));
    }

    private static List<String> details(String idField, String listField, List<String> duplicateIds) {
        return duplicateIds.stream()
                .map(id -> String.format(ErrorMessages.DUPLICATE_ID_DETAIL, listField, idField, ErrorMessages.echo(id)))
                .toList();
    }
}
